import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class DocsServer {
    private static final int PORTA = 5000;
    private static final String PASTA_ARQUIVOS = "./documentos_servidor";

    public static void main(String[] args) {
        // 1. Garante que a pasta onde os arquivos ficam salvos exista no servidor
        new File(PASTA_ARQUIVOS).mkdirs();

        // 2. Cria o ServerSocket na porta 5000
        try (ServerSocket serverSocket = new ServerSocket(PORTA)) {
            System.out.println("Servidor Google Docs TCP iniciado na porta " + PORTA);
            System.out.println("Aguardando conexões de usuários...");

            // Loop infinito para aceitar múltiplos usuários conectando
            while (true) {
                // O método accept() TRAVA a execução até que um cliente se conecte.
                // Quando conecta, ele retorna um objeto Socket exclusivo para aquele cliente.
                Socket clientSocket = serverSocket.accept();
                System.out.println("Usuário conectado: " + clientSocket.getInetAddress().getHostAddress());

                // 3. Cria uma nova Thread para atender esse usuário sem bloquear os próximos
                new Thread(new ManipuladorDeCliente(clientSocket)).start();
            }
        } catch (IOException e) {
            System.err.println("Erro no servidor: " + e.getMessage());
        }
    }

    // Classe interna que roda em uma Thread separada para cada usuário conectado
    private static class ManipuladorDeCliente implements Runnable {
        private Socket socket;

        public ManipuladorDeCliente(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            // 4. Abrimos os canais de Entrada (in) e Saída (out) usando UTF-8 para não quebrar acentos
            try (
                BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
                );
                // O 'true' no PrintWriter ativa o autoFlush (envia os dados imediatamente pela rede)
                PrintWriter out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true
                )
            ) {
                String payloadRecebido;

                // 5. Fica lendo cada comando enviado pelo usuário enquanto a conexão estiver aberta
                while ((payloadRecebido = in.readLine()) != null) {
                    System.out.println("Payload recebido: " + payloadRecebido);

                    // Quebra o payload em até 3 partes usando o separador "|"
                    // Formato: ACAO|NOME_ARQUIVO|CONTEUDO
                    String[] partes = payloadRecebido.split("\\|", 3);
                    if (partes.length < 2) {
                        out.println("ERRO|Payload inválido. Use ACAO|ARQUIVO|CONTEUDO");
                        continue;
                    }

                    String acao = partes[0];
                    String nomeArquivo = partes[1];
                    String conteudo = (partes.length == 3) ? partes[2] : "";

                    // Caminho seguro para o arquivo dentro da pasta do servidor
                    Path caminhoArquivo = Paths.get(PASTA_ARQUIVOS, nomeArquivo);

                    // 6. Roteamento da lógica baseada na ACAO do Payload
                    switch (acao.toUpperCase()) {
                        case "ABRIR":
                            if (Files.exists(caminhoArquivo)) {
                                // Lê todo o arquivo do disco
                                String textoDoArquivo = Files.readString(caminhoArquivo, StandardCharsets.UTF_8);
                                // Escapa as quebras de linha reais para enviar em uma única linha TCP
                                String textoSerializado = textoDoArquivo.replace("\n", "\\n").replace("\r", "");
                                out.println("OK|" + textoSerializado);
                            } else {
                                // Se não existir, cria um documento em branco (igual ao Google Docs)
                                Files.writeString(caminhoArquivo, "", StandardCharsets.UTF_8);
                                out.println("OK|");
                            }
                            break;

                        case "SALVAR":
                            // Synchronized impede que dois usuários corrompam o arquivo salvando no mesmo milissegundo
                            synchronized (DocsServer.class) {
                                // Desfaz o escape do \n para salvar no disco com as quebras de linha reais
                                String textoReal = conteudo.replace("\\n", "\n");
                                Files.writeString(caminhoArquivo, textoReal, StandardCharsets.UTF_8);
                            }
                            out.println("OK|Documento '" + nomeArquivo + "' salvo com sucesso no servidor!");
                            break;

                        default:
                            out.println("ERRO|Ação desconhecida: " + acao);
                    }
                }
            } catch (IOException e) {
                System.out.println("Usuário desconectou: " + e.getMessage());
            } finally {
                // 7. Garante o fechamento do socket ao encerrar
                try {
                    socket.close();
                } catch (IOException ignored) {}
            }
        }
    }
}