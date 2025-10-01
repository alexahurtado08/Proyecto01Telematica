// server.c - Servidor VAT-P para vehículo autónomo (usa pthreads y sockets)
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#include <arpa/inet.h>
#include <time.h>

#define PORT 5000
#define MAX_CLIENTS 16

typedef struct {
    float speed;
    int battery;
    float temperature;
    char direction[16];
} VehicleState;

typedef struct {
    int socket;
    char role[16];       // "ADMIN" o "OBSERVER"
    char session[64];    // token de sesión
    char id[64];         // ip:port
    int active;
} ClientInfo;

VehicleState vehicle = {20.0, 100, 25.0, "NORTH"};
ClientInfo clients[MAX_CLIENTS];
pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;

// Enviar texto a un socket
void send_client(int sock, const char *msg) {
    if(sock <= 0) return;
    send(sock, msg, strlen(msg), 0);
}

// Enviar telemetría en JSON
void broadcast_telemetry() {
    char buffer[512];

    // --- Ajustar temperatura en función de la velocidad ---
    if (vehicle.speed >= 15) {
        vehicle.temperature += 0.5;  // si se mueve rapido, se calienta
    } else {
        vehicle.temperature -= 0.2;  // si está quieto o se mueve lento, se enfría poco a poco
    }

    // límites
    if (vehicle.temperature < 20.0) vehicle.temperature = 20.0;
    if (vehicle.temperature > 100.0) vehicle.temperature = 100.0;

    // --- Armar mensaje de telemetría ---
    snprintf(buffer, sizeof(buffer),
        "TELEMETRY VAT-P/1.0\r\n"
        "Content-Type: application/json\r\n\r\n"
        "{\"speed\":%.1f,\"battery\":%d,\"temperature\":%.1f,\"direction\":\"%s\"}",
        vehicle.speed, vehicle.battery, vehicle.temperature, vehicle.direction);

    pthread_mutex_lock(&lock);
    for(int i=0;i<MAX_CLIENTS;i++){
        if(clients[i].active){
            send_client(clients[i].socket, buffer);
        }
    }
    pthread_mutex_unlock(&lock);
}

// Generar token simple
void gen_token(char *buf, size_t len) {
    const char *chars="abcdef0123456789";
    for(size_t i=0;i<len-1;i++){
        buf[i] = chars[rand()%16];
    }
    buf[len-1]='\0';
}

// Aplicar comando simple sobre el estado del vehículo
void process_command(const char *cmd) {
    if(strstr(cmd,"SPEED UP")) vehicle.speed += 5.0f;
    else if(strstr(cmd,"SLOW DOWN")) vehicle.speed = (vehicle.speed >= 5.0f ? vehicle.speed - 5.0f : 0.0f);
    else if(strstr(cmd,"TURN LEFT")) strncpy(vehicle.direction,"WEST",sizeof(vehicle.direction));
    else if(strstr(cmd,"TURN RIGHT")) strncpy(vehicle.direction,"EAST",sizeof(vehicle.direction));
    if(vehicle.battery > 0) vehicle.battery -= 1;
}

// Handler por cliente (cada conexión)
void *client_handler(void *arg) {
    int sock = *((int*)arg);
    free(arg);

    char buffer[2048];
    int idx = -1;

    // registrar cliente
    pthread_mutex_lock(&lock);
    for(int i=0;i<MAX_CLIENTS;i++){
        if(!clients[i].active){
            clients[i].socket = sock;
            clients[i].active = 1;
            strcpy(clients[i].role,"OBSERVER");
            clients[i].session[0]='\0';
            // obtener ip:port
            struct sockaddr_in addr;
            socklen_t len = sizeof(addr);
            if(getpeername(sock,(struct sockaddr*)&addr,&len)==0){
                snprintf(clients[i].id,sizeof(clients[i].id),"%s:%d",inet_ntoa(addr.sin_addr), ntohs(addr.sin_port));
            } else {
                snprintf(clients[i].id,sizeof(clients[i].id),"client-%d",i);
            }
            idx = i;
            break;
        }
    }
    pthread_mutex_unlock(&lock);

    // mensaje inicial
    if(idx >= 0){
        char welcome[128];
        snprintf(welcome,sizeof(welcome),"OK Connected (id=%s)\r\n", clients[idx].id);
        send_client(sock, welcome);
        printf("[+] Conectado: %s (slot %d)\n", clients[idx].id, idx);
    } else {
        send_client(sock, "ERROR 503 Server full\r\n");
        close(sock);
        return NULL;
    }

    while(1){
        memset(buffer,0,sizeof(buffer));
        int n = recv(sock, buffer, sizeof(buffer)-1, 0);
        if(n <= 0) break;

        // LOGIN
        if(strncmp(buffer,"LOGIN",5)==0){
            if(strstr(buffer,"Username: admin") && strstr(buffer,"Password: 1234")){
                pthread_mutex_lock(&lock);
                strcpy(clients[idx].role,"ADMIN");
                gen_token(clients[idx].session,sizeof(clients[idx].session));
                pthread_mutex_unlock(&lock);
                char resp[128];
                snprintf(resp,sizeof(resp),"200 OK SESSION %s TTL=3600\r\n", clients[idx].session);
                send_client(sock, resp);
                printf("[*] Admin autenticado: %s (token=%s)\n", clients[idx].id, clients[idx].session);
            } else {
                send_client(sock,"ERROR 401 Unauthorized\r\n");
            }
        }
        // LOGOUT
        else if(strncmp(buffer,"LOGOUT",6)==0){
            pthread_mutex_lock(&lock);
            strcpy(clients[idx].role,"OBSERVER");
            clients[idx].session[0]='\0';
            pthread_mutex_unlock(&lock);
            send_client(sock,"OK Logged out\r\n");
        }
        // CMD
        else if(strncmp(buffer,"CMD",3)==0){
            // verificar token si existe
            char token_recv[64]={0};
            char *p = strstr(buffer,"Session:");
            if(p) sscanf(p, "Session: %63s", token_recv);

            pthread_mutex_lock(&lock);
            if(strcmp(clients[idx].role,"ADMIN")==0 && (token_recv[0]==0 || strcmp(token_recv,clients[idx].session)==0)){
                process_command(buffer);
                send_client(sock,"OK Command executed\r\n");
                printf("[*] CMD ejecutado por %s. velocidad=%.1f battery=%d temp=%.1f dir=%s\n",
                    clients[idx].id, vehicle.speed, vehicle.battery, vehicle.temperature, vehicle.direction);
            } else {
                send_client(sock,"ERROR 403 Forbidden\r\n");
            }
            pthread_mutex_unlock(&lock);
        }
        // LIST (solo admin)
        else if(strncmp(buffer,"LIST",4)==0){
            pthread_mutex_lock(&lock);
            if(strcmp(clients[idx].role,"ADMIN")==0){
                char listbuf[1024];
                int off=0;
                off += snprintf(listbuf+off, sizeof(listbuf)-off, "OK ClientList:\n");
                for(int i=0;i<MAX_CLIENTS;i++){
                    if(clients[i].active){
                        off += snprintf(listbuf+off, sizeof(listbuf)-off, " - %s (%s)\n", clients[i].id, clients[i].role);
                    }
                }
                pthread_mutex_unlock(&lock);
                send_client(sock, listbuf);
            } else {
                pthread_mutex_unlock(&lock);
                send_client(sock,"ERROR 403 Forbidden\r\n");
            }
        }
        // PING
        else if(strncmp(buffer,"PING",4)==0){
            send_client(sock,"PONG\r\n");
        }
        // QUIT
        else if(strncmp(buffer,"QUIT",4)==0){
            break;
        }
        else {
            send_client(sock,"ERROR 400 Bad Request\r\n");
        }
    }

    close(sock);
    pthread_mutex_lock(&lock);
    if(idx>=0){
        clients[idx].active = 0;
        clients[idx].socket = 0;
    }
    pthread_mutex_unlock(&lock);
    printf("[-] Desconectado (slot %d)\n", idx);
    return NULL;
}

// hilo que manda telemetría cada 10 segundos
void *telemetry_thread(void *arg){
    (void)arg;
    while(1){
        sleep(10);
        broadcast_telemetry();
    }
    return NULL;
}

int main(){
    srand(time(NULL));
    for(int i=0;i<MAX_CLIENTS;i++) clients[i].active = 0;

    int server_fd;
    struct sockaddr_in address;
    socklen_t addrlen = sizeof(address);

    if((server_fd = socket(AF_INET, SOCK_STREAM, 0)) < 0){
        perror("socket");
        exit(1);
    }

    int opt = 1;
    setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    address.sin_family = AF_INET;
    address.sin_addr.s_addr = INADDR_ANY; // escuchar en todas las interfaces
    address.sin_port = htons(PORT);

    if(bind(server_fd, (struct sockaddr*)&address, sizeof(address)) < 0){
        perror("bind");
        exit(1);
    }

    if(listen(server_fd, 8) < 0){
        perror("listen");
        exit(1);
    }

    printf("Servidor VAT-P escuchando en puerto %d...\n", PORT);

    pthread_t tid_tel;
    pthread_create(&tid_tel, NULL, telemetry_thread, NULL);

    while(1){
        struct sockaddr_in client_addr;
        socklen_t client_len = sizeof(client_addr);
        int new_sock = accept(server_fd, (struct sockaddr*)&client_addr, &client_len);
        if(new_sock < 0) {
            perror("accept");
            continue;
        }
        int *pclient = malloc(sizeof(int));
        *pclient = new_sock;
        pthread_t tid;
        pthread_create(&tid, NULL, client_handler, pclient);
        pthread_detach(tid);
    }

    close(server_fd);
    return 0;
}
