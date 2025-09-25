import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.Executors;

public class VehiculoClienteJava extends JFrame {

    // Variables de red 
    private Socket socket;                 // Socket TCP para conexión con el servidor
    private PrintWriter out;               // Para enviar mensajes al servidor
    private BufferedReader in;             // Para leer respuestas del servidor
    private String sessionToken = null;    // Token de sesión al hacer login como admin
    private boolean isObserver = false;    // Bandera para saber si el cliente es observador

    // Componentes de la interfaz gráfica 
    private JTextField txtUsuario, txtPassword;
    private JLabel lblVelocidad, lblBateria, lblTemp, lblDir;
    private JButton btnLogin, btnAcelerar, btnFrenar, btnIzquierda, btnDerecha, btnConectar, btnSalir, btnObservador;

    public VehiculoClienteJava() {
        // Configuración principal de la ventana
        setTitle("Cliente Vehículo Autónomo - Java");
        setSize(420, 420);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // Panel de Login 
        JPanel panelLogin = new JPanel(new GridLayout(3, 2));
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

        add(panelLogin, BorderLayout.NORTH);

        //  Panel de Telemetría 
        JPanel panelTel = new JPanel(new GridLayout(4, 1));
        panelTel.setBorder(BorderFactory.createTitledBorder("Telemetría"));

        lblVelocidad = new JLabel("Velocidad: -- km/h");
        lblBateria = new JLabel("Batería: -- %");
        lblTemp = new JLabel("Temperatura: -- °C");
        lblDir = new JLabel("Dirección: --");

        panelTel.add(lblVelocidad);
        panelTel.add(lblBateria);
        panelTel.add(lblTemp);
        panelTel.add(lblDir);

        add(panelTel, BorderLayout.CENTER);

        // Panel de Comandos 
        JPanel panelCmd = new JPanel(new GridLayout(2, 2));
        panelCmd.setBorder(BorderFactory.createTitledBorder("Comandos"));

        btnAcelerar = new JButton("SPEED UP");
        btnFrenar = new JButton("SLOW DOWN");
        btnIzquierda = new JButton("TURN LEFT");
        btnDerecha = new JButton("TURN RIGHT");

        panelCmd.add(btnAcelerar);
        panelCmd.add(btnFrenar);
        panelCmd.add(btnIzquierda);
        panelCmd.add(btnDerecha);

        add(panelCmd, BorderLayout.SOUTH);

        //  Panel inferior (conectar/salir) 
        JPanel panelInferior = new JPanel();
        btnConectar = new JButton("Conectar Servidor");
        btnSalir = new JButton("Salir");
        panelInferior.add(btnConectar);
        panelInferior.add(btnSalir);

        add(panelInferior, BorderLayout.PAGE_END);

        //  Acciones de los botones
        btnConectar.addActionListener(e -> conectarServidor());
        btnLogin.addActionListener(e -> enviarLogin());
        btnObservador.addActionListener(e -> entrarObservador());
        btnSalir.addActionListener(e -> salir());

        btnAcelerar.addActionListener(e -> enviarComando("SPEED UP"));
        btnFrenar.addActionListener(e -> enviarComando("SLOW DOWN"));
        btnIzquierda.addActionListener(e -> enviarComando("TURN LEFT"));
        btnDerecha.addActionListener(e -> enviarComando("TURN RIGHT"));

        // Comandos desactivados por defecto (solo admin puede usarlos)
        setComandosEnabled(false);
    }

    // Conexión al servidor 
    private void conectarServidor() {
        try {
            socket = new Socket("127.0.0.1", 5000); // Conexión TCP al servidor
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            JOptionPane.showMessageDialog(this, "Conectado al servidor ");

            // Hilo para escuchar mensajes del servidor en segundo plano
            Executors.newSingleThreadExecutor().execute(this::escucharServidor);

        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Error al conectar: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    //  Escuchar mensajes del servidor 
    private void escucharServidor() {
        try {
            String linea;
            while ((linea = in.readLine()) != null) {
                // Respuesta al login (OK + token)
                if (linea.startsWith("200 OK SESSION")) {
                    String[] partes = linea.split(" ");
                    sessionToken = partes[3];
                    JOptionPane.showMessageDialog(this, "Sesión iniciada. Token: " + sessionToken);
                    setComandosEnabled(true);

                // Recepción de telemetría en JSON
                } else if (linea.startsWith("TELEMETRY")) {
                    String jsonBody = in.readLine(); // El JSON viene en la siguiente línea
                    actualizarTelemetria(jsonBody);

                } else {
                    System.out.println("Servidor: " + linea);
                }
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Conexión cerrada");
        }
    }

    // Enviar login como admin 
    private void enviarLogin() {
        isObserver = false;
        String usuario = txtUsuario.getText();
        String pass = txtPassword.getText();

        // Mensaje LOGIN según protocolo VAT-P
        String msg = "LOGIN / VAT-P/1.0\r\n" +
                     "Username: " + usuario + "\r\n" +
                     "Password: " + pass + "\r\n" +
                     "Role: ADMIN\r\n\r\n";
        out.println(msg);
    }

    //  Entrar como observador 
    private void entrarObservador() {
        isObserver = true;
        sessionToken = null;
        JOptionPane.showMessageDialog(this, "Entraste como observador \nSolo recibirás telemetría.");
        setComandosEnabled(false); // Observador no puede enviar comandos
    }

    // Enviar un comando al vehículo 
    private void enviarComando(String cmd) {
        if (sessionToken != null && !isObserver) {
            String msg = "CMD /control VAT-P/1.0\r\n" +
                         "Session: " + sessionToken + "\r\n" +
                         "Command: " + cmd + "\r\n\r\n";
            out.println(msg);
        } else {
            JOptionPane.showMessageDialog(this, " Solo los administradores pueden enviar comandos.");
        }
    }

    //  Mostrar la telemetría en los labels 
    private void actualizarTelemetria(String json) {
        SwingUtilities.invokeLater(() -> {
            if (json.contains("speed")) lblVelocidad.setText("Velocidad: " + extraerValor(json, "speed") + " km/h");
            if (json.contains("battery")) lblBateria.setText("Batería: " + extraerValor(json, "battery") + " %");
            if (json.contains("temperature")) lblTemp.setText("Temperatura: " + extraerValor(json, "temperature") + " °C");
            if (json.contains("direction")) lblDir.setText("Dirección: " + extraerValor(json, "direction"));
        });
    }

    //  Extraer valores de un JSON simple (sin librerías) 
    private String extraerValor(String json, String campo) {
        try {
            int start = json.indexOf(campo) + campo.length() + 3;
            int end = json.indexOf(",", start);
            if (end == -1) end = json.indexOf("}", start);
            return json.substring(start, end).replace("\"", "");
        } catch (Exception e) {
            return "--";
        }
    }

    // Habilitar/deshabilitar botones de comandos 
    private void setComandosEnabled(boolean enabled) {
        btnAcelerar.setEnabled(enabled);
        btnFrenar.setEnabled(enabled);
        btnIzquierda.setEnabled(enabled);
        btnDerecha.setEnabled(enabled);
    }

    // Salir del programa 
    private void salir() {
        try {
            if (out != null) out.println("QUIT / VAT-P/1.0\r\n\r\n");
            if (socket != null) socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        System.exit(0);
    }

    //  Método principal 
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new VehiculoClienteJava().setVisible(true));
    }
}
