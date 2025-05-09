package co.AndrewP05;

import com.rabbitmq.client.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;

public class EcommerceConsumer {
    private static final String HOST = "localhost";
    private static final String PRODUCTO_DIRECT_EXCHANGE = "producto_directo";
    private static final String PRODUCTOS_TOPIC_EXCHANGE = "productos_topic";
    private static final String OFERTAS_FANOUT_EXCHANGE = "ofertas_fanout";
    private static final String COLA_COMPRAS = "cola_compras";
    private static final String COLA_PRODUCTOS = "cola_productos"; // nueva cola para productos
    private static final String COLA_ACTUALIZACION = "cola_actualizacion";
    
    private JFrame frame;
    private DefaultListModel<String> listModelProductos;
    private JList<String> listProductos;
    private JTextArea txtDetallesProducto;
    private JComboBox<String> cbCantidad;
    private JTextArea txtOfertas;
    private JTextArea logArea;
    private JTextField txtNombreCliente;
    
    private final Map<String, Map<String, String>> productos = new LinkedHashMap<>();
    private final Map<String, Integer> stockActual = new HashMap<>();
    private Channel channel;

    public static void main(String[] args) {
        EventQueue.invokeLater(() -> {
            try {
                EcommerceConsumer window = new EcommerceConsumer();
                window.frame.setVisible(true);
                window.setupRabbitMQ();
                window.iniciarConsumidores();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public EcommerceConsumer() {
        initialize();
    }

    private void initialize() {
        frame = new JFrame("Sistema de E-commerce - Consumer");
        frame.setBounds(100, 100, 900, 700);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.getContentPane().setLayout(new BorderLayout(10, 10));

        JPanel panelIzquierdo = new JPanel(new BorderLayout());
        panelIzquierdo.setBorder(BorderFactory.createTitledBorder("Productos Disponibles"));
        listModelProductos = new DefaultListModel<>();
        listProductos = new JList<>(listModelProductos);
        listProductos.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        listProductos.addListSelectionListener(e -> mostrarDetallesProducto());
        panelIzquierdo.add(new JScrollPane(listProductos), BorderLayout.CENTER);
        frame.getContentPane().add(panelIzquierdo, BorderLayout.WEST);

        JPanel panelCentral = new JPanel(new BorderLayout());
        txtDetallesProducto = new JTextArea(10, 30);
        txtDetallesProducto.setEditable(false);
        txtDetallesProducto.setLineWrap(true);
        txtDetallesProducto.setWrapStyleWord(true);
        panelCentral.add(new JScrollPane(txtDetallesProducto), BorderLayout.CENTER);
        JPanel panelCompra = new JPanel(new GridLayout(0, 2, 10, 10));
        panelCompra.setBorder(BorderFactory.createTitledBorder("Datos de Compra"));
        panelCompra.add(new JLabel("Nombre del Cliente:"));
        txtNombreCliente = new JTextField();
        panelCompra.add(txtNombreCliente);
        panelCompra.add(new JLabel("Cantidad disponible:"));
        cbCantidad = new JComboBox<>();
        panelCompra.add(cbCantidad);
        JButton btnComprar = new JButton("Comprar Producto");
        btnComprar.addActionListener(this::comprarProducto);
        panelCompra.add(btnComprar);
        panelCentral.add(panelCompra, BorderLayout.SOUTH);
        frame.getContentPane().add(panelCentral, BorderLayout.CENTER);

        JPanel panelDerecho = new JPanel(new BorderLayout());
        panelDerecho.setBorder(BorderFactory.createTitledBorder("Ofertas"));
        txtOfertas = new JTextArea(10, 20);
        txtOfertas.setEditable(false);
        panelDerecho.add(new JScrollPane(txtOfertas), BorderLayout.CENTER);
        frame.getContentPane().add(panelDerecho, BorderLayout.EAST);

        logArea = new JTextArea();
        logArea.setEditable(false);
        frame.getContentPane().add(new JScrollPane(logArea), BorderLayout.SOUTH);
    }

    private void setupRabbitMQ() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(HOST);
        Connection connection = factory.newConnection();
        channel = connection.createChannel();

        // Declarar exchanges y colas
        channel.exchangeDeclare(PRODUCTO_DIRECT_EXCHANGE, BuiltinExchangeType.DIRECT, true);
        channel.exchangeDeclare(PRODUCTOS_TOPIC_EXCHANGE, BuiltinExchangeType.TOPIC, true);
        channel.exchangeDeclare(OFERTAS_FANOUT_EXCHANGE, BuiltinExchangeType.FANOUT, true);

        // Cola de compras (para ver las compras)
        channel.queueDeclare(COLA_COMPRAS, true, false, false, null);
        channel.queueBind(COLA_COMPRAS, PRODUCTO_DIRECT_EXCHANGE, "compra");

        // Cola de productos (para ver publicaciones)
        channel.queueDeclare(COLA_PRODUCTOS, true, false, false, null);
        channel.queueBind(COLA_PRODUCTOS, PRODUCTOS_TOPIC_EXCHANGE, "producto.*");

        // Cola de ofertas globales
        String queueFanout = channel.queueDeclare().getQueue();
        channel.queueBind(queueFanout, OFERTAS_FANOUT_EXCHANGE, "");

        log("RabbitMQ configurado: colas compras, productos y ofertas creadas");
    }

    private void iniciarConsumidores() {
        try {
            // Consumidor de productos en UI
            channel.basicConsume(COLA_PRODUCTOS, true, new DefaultConsumer(channel) {
                @Override
                public void handleDelivery(String consumerTag, Envelope envelope,
                                           AMQP.BasicProperties properties, byte[] body) throws IOException {
                    String message = new String(body, StandardCharsets.UTF_8);
                    SwingUtilities.invokeLater(() -> procesarNuevoProducto(message));
                }
            });

            // Consumidor de compras (log)
            channel.basicConsume(COLA_COMPRAS, true, new DefaultConsumer(channel) {
                @Override
                public void handleDelivery(String consumerTag, Envelope envelope,
                                           AMQP.BasicProperties properties, byte[] body) throws IOException {
                    String compra = new String(body, StandardCharsets.UTF_8);
                    SwingUtilities.invokeLater(() -> log("Compra registrada en servidor: " + compra));
                }
            });

            // Consumidor de ofertas UI
            String queueFanout = channel.queueDeclare().getQueue();
            channel.queueBind(queueFanout, OFERTAS_FANOUT_EXCHANGE, "");
            channel.basicConsume(queueFanout, true, new DefaultConsumer(channel) {
                @Override
                public void handleDelivery(String consumerTag, Envelope envelope,
                                           AMQP.BasicProperties properties, byte[] body) throws IOException {
                    String message = new String(body, StandardCharsets.UTF_8);
                    SwingUtilities.invokeLater(() -> mostrarOferta(message));
                }
            });

            log("Consumidores iniciados. Esperando mensajes...");
        } catch (Exception e) {
            log("Error al iniciar consumidores: " + e.getMessage());
        }
    }

    private void procesarNuevoProducto(String message) {
        Map<String, String> productoMap = stringToMap(message);
        String nombre = productoMap.get("nombre");
        if (nombre != null) {
            productos.put(nombre, productoMap);
            int stock = Integer.parseInt(productoMap.getOrDefault("stock", "0"));
            stockActual.put(nombre, stock);
            SwingUtilities.invokeLater(() -> {
                if (!listModelProductos.contains(nombre)) {
                    listModelProductos.addElement(nombre);
                }
                log("Producto actualizado: " + nombre);
            });
        }
    }

    private void mostrarOferta(String oferta) {
        txtOfertas.append(oferta + "\n\n");
        log("Nueva oferta recibida");
    }

    private void mostrarDetallesProducto() {
        String nombre = listProductos.getSelectedValue();
        if (nombre != null && productos.containsKey(nombre)) {
            Map<String, String> productoMap = productos.get(nombre);
            int stock = stockActual.getOrDefault(nombre, 0);
            String detalles = String.format(
                "Nombre: %s\nCategoría: %s\nFecha: %s\nMarca: %s\nSección: %s\nPrecio: $%s\nStock disponible: %d",
                nombre,
                productoMap.get("categoria"),
                productoMap.get("fecha_publicacion"),
                productoMap.get("marca"),
                productoMap.get("seccion"),
                productoMap.get("precio"),
                stock
            );
            txtDetallesProducto.setText(detalles);
            cbCantidad.removeAllItems();
            for (int i = 1; i <= stock; i++) {
                cbCantidad.addItem(String.valueOf(i));
            }
        }
    }

    private void comprarProducto(ActionEvent e) {
        String nombre = listProductos.getSelectedValue();
        String cantidad = (String) cbCantidad.getSelectedItem();
        String cliente = txtNombreCliente.getText();
        if (nombre == null || cantidad == null || cliente.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Complete todos los campo", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            // Publicar compra en exchange directo
            String mensaje = nombre + ":" + cantidad + ";cliente:" + cliente;
            channel.basicPublish(PRODUCTO_DIRECT_EXCHANGE, "compra", null, mensaje.getBytes(StandardCharsets.UTF_8));
            // Actualizar stock local y en UI
            int restante = stockActual.get(nombre) - Integer.parseInt(cantidad);
            stockActual.put(nombre, restante);
            mostrarDetallesProducto();
            txtNombreCliente.setText("");
            JOptionPane.showMessageDialog(frame, "Compra exitosa", "Éxito", JOptionPane.INFORMATION_MESSAGE);
            log("Compra enviada: " + mensaje);
        } catch (IOException ex) {
            log("Error al registrar compra: " + ex.getMessage());
            JOptionPane.showMessageDialog(frame, "Error al registrar la compra", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private Map<String, String> stringToMap(String str) {
        Map<String, String> map = new HashMap<>();
        for (String entry : str.split(";")) {
            String[] kv = entry.split(":");
            if (kv.length == 2) {
                map.put(kv[0], kv[1]);
            }
        }
        return map;
    }

    private void log(String message) {
        logArea.append(message + "\n");
    }
}
