import socket  
import threading
import json
import tkinter as tk
from tkinter import messagebox


# CONFIGURACIÓN DEL CLIENTE
SERVER_HOST = "127.0.0.1"   # Dirección del servidor (localhost)
SERVER_PORT = 5000          # Puerto del servidor

session_token = None        # Token de sesión cuando el admin hace login
sock = None                 # Socket TCP
is_observer = False         # Bandera para diferenciar rol: admin u observador


# FUNCIÓN QUE ESCUCHA AL SERVIDOR

def listen_server():
    global session_token
    while True:
        try:
            data = sock.recv(4096).decode("utf-8")  # Recibir datos del servidor
            if not data:
                break

            # Si el servidor confirma sesión de admin
            if data.startswith("200 OK SESSION"):
                parts = data.split()
                session_token = parts[3] if len(parts) >= 4 else None
                messagebox.showinfo("Login", f" Sesión iniciada: {session_token}")
                enable_commands(True)  # Habilitar botones de comandos

            # Si el servidor envía telemetría
            elif data.startswith("TELEMETRY"):
                body = data.split("\r\n")[-1]
                try:
                    telemetry = json.loads(body)  # Convertir JSON a diccionario
                    update_telemetry(telemetry)  # Mostrar telemetría en GUI
                except:
                    print(" Error decodificando telemetría")

            else:
                print("Servidor:", data)

        except Exception as e:
            print(" Error en recepción:", e)
            break


# CONECTARSE AL SERVIDOR

def connect_server():
    global sock
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.connect((SERVER_HOST, SERVER_PORT))    # Conectar al servidor
        threading.Thread(target=listen_server, daemon=True).start()
        messagebox.showinfo("Conexión", " Conectado al servidor")
    except Exception as e:
        messagebox.showerror("Error", f"No se pudo conectar: {e}")


# LOGIN COMO ADMIN

def send_login():
    global session_token, is_observer
    username = entry_user.get()
    password = entry_pass.get()

    # Mensaje LOGIN según protocolo VAT-P
    msg = f"LOGIN / VAT-P/1.0\r\nUsername: {username}\r\nPassword: {password}\r\nRole: ADMIN\r\n\r\n"
    sock.sendall(msg.encode("utf-8"))
    is_observer = False


# ENTRAR COMO OBSERVADOR

def enter_observer():
    global is_observer, session_token
    is_observer = True
    session_token = None
    messagebox.showinfo("Observador", " Entraste como Observador. Solo recibirás telemetría.")
    enable_commands(False)   # Observador no puede mandar comandos


# ENVIAR COMANDOS AL VEHÍCULO

def send_command(cmd):
    global session_token, is_observer
    if session_token and not is_observer:
        msg = f"CMD /control VAT-P/1.0\r\nSession: {session_token}\r\nCommand: {cmd}\r\n\r\n"
        sock.sendall(msg.encode("utf-8"))
    else:
        messagebox.showwarning("Aviso", " Solo los administradores pueden enviar comandos")


# CERRAR CONEXIÓN

def send_quit():
    if sock:
        try:
            sock.sendall(b"QUIT / VAT-P/1.0\r\n\r\n")
        except:
            pass
        sock.close()
    root.quit()


# FUNCIONES DE INTERFAZ GRÁFICA

def update_telemetry(data):
    """Actualizar labels con la telemetría recibida"""
    lbl_speed.config(text=f"Velocidad: {data.get('speed', '--')} km/h")
    lbl_bat.config(text=f"Batería: {data.get('battery', '--')} %")
    lbl_temp.config(text=f"Temperatura: {data.get('temperature', '--')} °C")
    lbl_dir.config(text=f"Dirección: {data.get('direction', '--')}")

def enable_commands(enabled):
    """Habilitar o deshabilitar los botones de comandos"""
    btn_up.config(state="normal" if enabled else "disabled")
    btn_down.config(state="normal" if enabled else "disabled")
    btn_left.config(state="normal" if enabled else "disabled")
    btn_right.config(state="normal" if enabled else "disabled")


# CONSTRUCCIÓN DE LA GUI (Tkinter)

root = tk.Tk()
root.title("Cliente Vehículo Autónomo - Python")
root.geometry("420x420")

# --- Frame de login ---
frame_login = tk.LabelFrame(root, text="Login", padx=10, pady=10)
frame_login.pack(pady=10, fill="x")

tk.Label(frame_login, text="Usuario:").grid(row=0, column=0, sticky="w")
entry_user = tk.Entry(frame_login)
entry_user.grid(row=0, column=1, padx=5)

tk.Label(frame_login, text="Contraseña:").grid(row=1, column=0, sticky="w")
entry_pass = tk.Entry(frame_login, show="*")
entry_pass.grid(row=1, column=1, padx=5)

btn_login = tk.Button(frame_login, text="Login Admin", width=15, command=send_login)
btn_login.grid(row=2, column=0, pady=5)

btn_observer = tk.Button(frame_login, text="Entrar como Observador", width=20, command=enter_observer)
btn_observer.grid(row=2, column=1, pady=5)

# --- Frame de telemetría ---
frame_tel = tk.LabelFrame(root, text="Telemetría", padx=10, pady=10)
frame_tel.pack(pady=10, fill="x")

lbl_speed = tk.Label(frame_tel, text="Velocidad: -- km/h")
lbl_speed.pack(anchor="w")
lbl_bat = tk.Label(frame_tel, text="Batería: -- %")
lbl_bat.pack(anchor="w")
lbl_temp = tk.Label(frame_tel, text="Temperatura: -- °C")
lbl_temp.pack(anchor="w")
lbl_dir = tk.Label(frame_tel, text="Dirección: --")
lbl_dir.pack(anchor="w")

# --- Frame de comandos ---
frame_cmd = tk.LabelFrame(root, text="Comandos", padx=10, pady=10)
frame_cmd.pack(pady=10, fill="x")

btn_up = tk.Button(frame_cmd, text="SPEED UP", width=12, command=lambda: send_command("SPEED UP"))
btn_up.grid(row=0, column=0, padx=5, pady=5)

btn_down = tk.Button(frame_cmd, text="SLOW DOWN", width=12, command=lambda: send_command("SLOW DOWN"))
btn_down.grid(row=0, column=1, padx=5, pady=5)

btn_left = tk.Button(frame_cmd, text="TURN LEFT", width=12, command=lambda: send_command("TURN LEFT"))
btn_left.grid(row=1, column=0, padx=5, pady=5)

btn_right = tk.Button(frame_cmd, text="TURN RIGHT", width=12, command=lambda: send_command("TURN RIGHT"))
btn_right.grid(row=1, column=1, padx=5, pady=5)

# --- Botones finales ---
frame_footer = tk.Frame(root)
frame_footer.pack(pady=10)

btn_connect = tk.Button(frame_footer, text="Conectar Servidor", width=18, command=connect_server)
btn_connect.pack(side="left", padx=5)

btn_exit = tk.Button(frame_footer, text="Salir", width=10, command=send_quit)
btn_exit.pack(side="right", padx=5)

# Al inicio, desactivar comandos (solo admin los activa)
enable_commands(False)

root.mainloop()
