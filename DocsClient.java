import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class DocsClient {
    // Se o servidor estiver em outra máquina na rede local, troque "localhost" pelo IP dela (ex: "192.168.1.15")
    private static final String IP_SERVIDOR = "localhost";
    private static final int PORTA_SERVIDOR = 5000;

    public static void main(String[] args) {
        // 1. O cliente cria o Socket. Neste exato momento o Java faz o Handshake TCP com o Servidor!
        try (
            Socket socket = new Socket(IP_SERVIDOR, PORTA_SERVIDOR);
            BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
            );
            PrintWriter out = new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true
            )
        ) {
            System.out.println("Conectado ao Servidor de Documentos!");

            String nomeDoc = "projeto_tcc.txt";

            // --- PASSO 1: SIMULANDO A ABERTURA DE UM DOCUMENTO ---
            String payloadAbrir = "ABRIR|" + nomeDoc + "|";
            System.out.println("\n[Enviando Payload]: " + payloadAbrir);
            out.println(payloadAbrir); // Envia pela rede

            // Aguarda a resposta do servidor
            String respostaAbrir = in.readLine();
            System.out.println("[Resposta do Servidor]: " + respostaAbrir);


            // --- PASSO 2: SIMULANDO O USUÁRIO DIGITANDO E SALVANDO ---
            String textoDigitadoPeloUsuario = "Título do Documento\nEste texto foi enviado via TCP para o servidor.";
            // Prepara o payload escapando o \n para não quebrar o protocolo de linha
            String conteudoSerializado = textoDigitadoPeloUsuario.replace("\n", "\\n");
            
            String payloadSalvar = "SALVAR|" + nomeDoc + "|" + conteudoSerializado;
            System.out.println("\n[Enviando Payload]: " + payloadSalvar);
            out.println(payloadSalvar); // Envia pela rede

            // Aguarda confirmação de salvamento
            String respostaSalvar = in.readLine();
            System.out.println("[Resposta do Servidor]: " + respostaSalvar);


            // --- PASSO 3: LENDO NOVAMENTE PARA CONFIRMAR QUE GRAVOU NO SERVIDOR ---
            out.println("ABRIR|" + nomeDoc + "|");
            String respostaLeitura = in.readLine();
            
            // Separa o STATUS do CONTEUDO ("OK|conteudo...")
            String[] partesResposta = respostaLeitura.split("\\|", 2);
            if (partesResposta[0].equals("OK")) {
                String conteudoRecebido = partesResposta[1].replace("\\n", "\n");
                System.out.println("\n--- CONTEÚDO ATUAL DO DOCUMENTO NO SERVIDOR ---");
                System.out.println(conteudoRecebido);
                System.out.println("-----------------------------------------------");
            }

        } catch (IOException e) {
            System.err.println("Erro ao conectar ao servidor: " + e.getMessage());
        }
    }
}