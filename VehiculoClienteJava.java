import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.Executors;

public class VehiculoClienteJava extends JFrame {

    // Variables de red
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private String sessionToken = null;
    private boolean isObserver = false;

    // Componentes de la interfaz gráfica
    private JTextField txtUsuario, txtPassword;
    private JLabel lblVelocidad, lblBateria, lblTemp, lblDir;
    private JButton btnLogin, btnAcelerar, btnFrenar, btnIzquierda, btnDerecha, btnConectar, btnSalir, btnObservador;

    public VehiculoClienteJava() {
        setTitle("Cliente Vehículo Autónomo - Java");
        setSize(450, 550);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BoxLayout(getContentPane(), BoxLayout.Y_AXIS));

        // ---------------- Panel de Login ----------------
        JPanel panelLogin = new JPanel(new GridLayout(3, 2, 5, 5));
        panelLogin.setBorder(BorderFactory.createTitledBorder("Login"));

        panelLogin.add(new JLabel("Usuario:"));
        txtUsuario = new JTextField();
        panelLogin.add(txtUsuario);

        panelLogin.add(new JLabel("Contraseña:"));
        txtPassword = new JPasswordField();
        panelLogin.add(txtPassword);

        btnLogin = new JButton("Login Admin");
        panelLogin.add(btnLogin);

        btnObservador = new JButton("Entrar como Observador");
        panelLogin.add(btnObservador);

        add(panelLogin);

        // ---------------- Panel de Telemetría ----------------
        JPanel panelTel = new JPanel(new GridLayout(4, 1, 5, 5));
        panelTel.setBorder(BorderFactory.createTitledBorder("Telemetría"));

        lblVelocidad = new JLabel("Velocidad: -- km/h");
        lblBateria = new JLabel("Batería: -- %");
        lblTemp = new JLabel("Temperatura: -- °C");
        lblDir = new JLabel("Dirección: --");

        panelTel.add(lblVelocidad);
        panelTel.add(lblBateria);
        panelTel.add(lblTemp);
        panelTel.add(lblDir);

        add(panelTel);

        // ---------------- Panel de Comandos ----------------
        JPanel panelCmd = new JPanel(new GridLayout(2, 2, 10, 10));
        panelCmd.setBorder(BorderFactory.createTitledBorder("Comandos"));

        btnAcelerar = new JButton("SPEED UP");
        btnFrenar = new JButton("SLOW DOWN");
        btnIzquierda = new JButton("TURN LEFT");
        btnDerecha = new JButton("TURN RIGHT");

        panelCmd.add(btnAcelerar);
        panelCmd.add(btnFrenar);
        panelCmd.add(btnIzquierda);
        panelCmd.add(btnDerecha);

        add(panelCmd);

        // ---------------- Panel Inferior ----------------
        JPanel panelInferior = new JPanel(new FlowLayout());
        btnConectar = new JButton("Conectar Servidor");
        btnSalir = new JButton("Salir");
        panelInferior.add(btnConectar);
        panelInferior.add(btnSalir);

        add(panelInferior);

        // ---------------- Acciones de botones ----------------
        btnConectar.addActionListener(e -> conectarServidor());
        btnLogin.addActionListener(e -> enviarLogin());
        btnObservador.addActionListener(e -> entrarObservador());
        btnSalir.addActionListener(e -> salir());

        // Botones ahora solo envían comandos al servidor
        btnAcelerar.addActionListener(e -> enviarComando("SPEED UP"));
        btnFrenar.addActionListener(e -> enviarComando("SLOW DOWN"));
        btnIzquierda.addActionListener(e -> enviarComando("TURN LEFT"));
        btnDerecha.addActionListener(e -> enviarComando("TURN RIGHT"));

        // Comandos desactivados por defecto
        setComandosEnabled(false);
    }

    // ---------------- Conexión al servidor ----------------
    private void conectarServidor() {
        try {
            socket = new Socket("127.0.0.1", 5000);
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            JOptionPane.showMessageDialog(this, "Conectado al servidor");

            // Escuchar mensajes del servidor en un hilo separado
            Executors.newSingleThreadExecutor().execute(this::escucharServidor);

        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Error al conectar: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void escucharServidor() {
        try {
            String linea;
            while ((linea = in.readLine()) != null) {
                System.out.println("DEBUG - Recibido: " + linea);
                
                if (linea.startsWith("200 OK SESSION")) {
                    String[] partes = linea.split(" ");
                    sessionToken = partes[3];
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(this, "Sesión iniciada como Administrador.\nToken: " + sessionToken);
                        setComandosEnabled(true);
                    });
                    isObserver = false;
                } else if (linea.startsWith("TELEMETRY")) {
                    // Leer las siguientes líneas hasta encontrar el JSON
                    StringBuilder message = new StringBuilder();
                    message.append(linea).append("\n");
                    
                    // Leer headers
                    while ((linea = in.readLine()) != null && !linea.isEmpty()) {
                        message.append(linea).append("\n");
                    }
                    
                    // Leer cuerpo JSON
                    if (linea != null && linea.isEmpty()) {
                        String jsonLine = in.readLine();
                        if (jsonLine != null && jsonLine.startsWith("{")) {
                            actualizarTelemetria(jsonLine);
                        }
                    }
                } else if (linea.startsWith("OK Command executed")) {
                    System.out.println("Comando ejecutado exitosamente");
                } else if (linea.startsWith("ERROR")) {
                    System.out.println("Error del servidor: " + linea);
                }
            }
        } catch (IOException e) {
            SwingUtilities.invokeLater(() -> 
                JOptionPane.showMessageDialog(this, "Conexión cerrada: " + e.getMessage()));
        }
    }

    // ---------------- Enviar login y comandos ----------------
    private void enviarLogin() {
        isObserver = false;
        String usuario = txtUsuario.getText();
        String pass = txtPassword.getText();

        String msg = "LOGIN / VAT-P/1.0\r\n" +
                     "Username: " + usuario + "\r\n" +
                     "Password: " + pass + "\r\n" +
                     "Role: ADMIN\r\n\r\n";
        out.println(msg);
    }

    private void entrarObservador() {
        isObserver = true;
        sessionToken = null;
        JOptionPane.showMessageDialog(this, "Entraste como observador.\nSolo recibirás telemetría.");
        setComandosEnabled(false);
    }

    private void enviarComando(String cmd) {
        if (sessionToken != null && !isObserver) {
            String msg = "CMD /control VAT-P/1.0\r\n" +
                        "Session: " + sessionToken + "\r\n" +
                        "Command: " + cmd + "\r\n\r\n";
            out.println(msg);
            
            // Opcional: Mostrar confirmación
            JOptionPane.showMessageDialog(this, "Comando enviado: " + cmd);
        } else {
            JOptionPane.showMessageDialog(this, "Solo los administradores pueden enviar comandos.");
        }
    }   

    // ---------------- Actualización de labels ----------------
    private void actualizarTelemetria(String json) {
        SwingUtilities.invokeLater(() -> {
            lblVelocidad.setText("Velocidad: " + getValor(json,"speed") + " km/h");
            lblBateria.setText("Batería: " + getValor(json,"battery") + " %");
            lblTemp.setText("Temperatura: " + getValor(json,"temperature") + " °C");
            lblDir.setText("Dirección: " + getValor(json,"direction"));
        });
    }

    private String getValor(String json, String key) {
        try {
            String search = "\"" + key + "\":";
            int start = json.indexOf(search);
            if (start == -1) return "--";
            
            start += search.length();
            
            // Buscar el valor
            int end = json.indexOf(",", start);
            if (end == -1) end = json.indexOf("}", start);
            if (end == -1) return "--";
            
            String val = json.substring(start, end).trim();
            
            // Remover comillas si las tiene
            if (val.startsWith("\"")) {
                val = val.replace("\"", "");
            }
            
            return val;
        } catch(Exception e) { 
            System.err.println("Error parsing JSON key '" + key + "': " + e.getMessage());
            return "--"; 
        }
    }

    private void setComandosEnabled(boolean enabled) {
        btnAcelerar.setEnabled(enabled);
        btnFrenar.setEnabled(enabled);
        btnIzquierda.setEnabled(enabled);
        btnDerecha.setEnabled(enabled);
    }

    private void salir() {
        try {
            if(out != null) out.println("QUIT / VAT-P/1.0\r\n\r\n");
            if(socket != null) socket.close();
        } catch (IOException e) { e.printStackTrace(); }
        System.exit(0);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new VehiculoClienteJava().setVisible(true));
    }
}
