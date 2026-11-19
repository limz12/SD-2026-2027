package tpf;

// o Chunck funciona como uma caixa que guarda as mensagens que sao enviadas para o #arquivo
// ex: mensagem 1: ola , mensagem 2: esta tudo?
// o chunck guarda as 2 mensagens e fecha quuando atingir o CHUNK_MAX_BYTES ou passarem SELAGEM_MS (5segundos)
public class Wire {
    
    public static final int CHUNK_MAX_BYTES =
            Integer.getInteger("tpf.chunk.max.bytes", 65536);
    
    //acrescenta este valor ao tamanho do chunck para que o chunck seja fechado antes de atingir o limite do discord (8MB)
    public static final int PAD_BYTES =
            Integer.getInteger("tpf.pad.bytes", 4096);

    public static final long SELAGEM_MS =
            Long.getLong("tpf.selagem.ms", 5000L);

    private Wire() {
        
    }
}
