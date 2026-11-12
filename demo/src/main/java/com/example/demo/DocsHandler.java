package com.example.demo;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

public class DocsHandler extends TextWebSocketHandler {

    private static final String PASTA_ARQUIVOS = "./documentos_servidor";
    private static final Set<WebSocketSession> usuarios = new CopyOnWriteArraySet<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        new File(PASTA_ARQUIVOS).mkdirs();
        usuarios.add(session);
        System.out.println("Usuário conectou pelo navegador! ID: " + session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payloadRecebido = message.getPayload();
        System.out.println("Payload recebido: " + payloadRecebido);

        String[] partes = payloadRecebido.split("\\|", 3);
        String acao = partes[0].toUpperCase();

        switch (acao) {
            case "MSG":
                String msg = (partes.length >= 2) ? partes[1] : "";
                enviarParaTodos("MSG|" + msg);
                break;

            case "ABRIR":
                String nomeAbrir = partes[1];
                Path caminhoAbrir = Paths.get(PASTA_ARQUIVOS, nomeAbrir);
                if (Files.exists(caminhoAbrir)) {
                    String texto = Files.readString(caminhoAbrir, StandardCharsets.UTF_8);
                    session.sendMessage(new TextMessage("OK|" + texto));
                } else {
                    Files.writeString(caminhoAbrir, "", StandardCharsets.UTF_8);
                    session.sendMessage(new TextMessage("OK|"));
                }
                break;

            case "SALVAR":
                String nomeSalvar = partes[1];
                String conteudo = (partes.length == 3) ? partes[2] : "";
                Path caminhoSalvar = Paths.get(PASTA_ARQUIVOS, nomeSalvar);
                Files.writeString(caminhoSalvar, conteudo, StandardCharsets.UTF_8);

                enviarParaTodos("ATUALIZAR|" + nomeSalvar + "|" + conteudo);
                break;
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        usuarios.remove(session);
    }

    private void enviarParaTodos(String texto) throws IOException {
        for (WebSocketSession s : usuarios) {
            if (s.isOpen()) {
                s.sendMessage(new TextMessage(texto));
            }
        }
    }
}