package com.cloudstorage;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

final class LocalSmtpInbox implements AutoCloseable {
    private final ServerSocket listener;
    private final BlockingQueue<MimeMessage> messages = new LinkedBlockingQueue<>();

    LocalSmtpInbox() {
        try {
            listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        Thread.ofVirtual().start(this::accept);
    }

    int port() {
        return listener.getLocalPort();
    }

    MimeMessage receive() throws InterruptedException {
        MimeMessage message = messages.poll(5, TimeUnit.SECONDS);
        if (message == null) {
            throw new AssertionError("The local SMTP inbox received no email");
        }
        return message;
    }

    boolean isEmpty() {
        return messages.isEmpty();
    }

    private void accept() {
        while (!listener.isClosed()) {
            try {
                Socket connection = listener.accept();
                Thread.ofVirtual().start(() -> handle(connection));
            } catch (IOException exception) {
                if (!listener.isClosed()) throw new UncheckedIOException(exception);
            }
        }
    }

    private void handle(Socket connection) {
        try (connection;
             var input = new BufferedReader(new InputStreamReader(connection.getInputStream(),
                     StandardCharsets.ISO_8859_1));
             var output = new BufferedWriter(new OutputStreamWriter(connection.getOutputStream(),
                     StandardCharsets.US_ASCII))) {
            connection.setSoTimeout(5000);
            reply(output, "220 local test inbox");
            String line;
            while ((line = input.readLine()) != null) {
                String command = line.split(" ", 2)[0].toUpperCase(Locale.ROOT);
                switch (command) {
                    case "EHLO", "HELO", "MAIL", "RCPT", "RSET", "NOOP" -> reply(output, "250 OK");
                    case "DATA" -> {
                        reply(output, "354 End with a dot");
                        StringBuilder data = new StringBuilder();
                        while ((line = input.readLine()) != null && !line.equals(".")) {
                            data.append(line.startsWith("..") ? line.substring(1) : line).append("\r\n");
                        }
                        byte[] bytes = data.toString().getBytes(StandardCharsets.ISO_8859_1);
                        messages.add(new MimeMessage(Session.getInstance(new Properties()),
                                new ByteArrayInputStream(bytes)));
                        reply(output, "250 Accepted");
                    }
                    case "QUIT" -> {
                        reply(output, "221 Bye");
                        return;
                    }
                    default -> reply(output, "502 Unsupported command");
                }
            }
        } catch (Exception exception) {
            // Never print the email body or SMTP input.
            throw new IllegalStateException("Local SMTP fixture failed", exception);
        }
    }

    private void reply(BufferedWriter output, String line) throws IOException {
        output.write(line + "\r\n");
        output.flush();
    }

    @Override
    public void close() throws IOException {
        listener.close();
    }
}
