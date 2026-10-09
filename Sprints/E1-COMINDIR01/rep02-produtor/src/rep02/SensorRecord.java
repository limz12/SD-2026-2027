public class SensorRecord {
    private long seq;
    private String sensor;
    private double temperatura;
    private String instante;

    public SensorRecord(long seq, String sensor, double temperatura, String instante) {
        this.seq = seq;
        this.sensor = sensor;
        this.temperatura = temperatura;
        this.instante = instante;
    }

    public long getSeq() {
        return seq;
    }

    public String getSensor() {
        return sensor;
    }

    public double getTemperatura() {
        return temperatura;
    }

    public String getInstante() {
        return instante;
    }

    // Converte o objeto para o formato de texto guardado no ficheiro
    public String toLine() {
        return seq + ";" + sensor + ";" + temperatura + ";" + instante;
    }

    // Cria um SensorRecord a partir de uma linha de texto do ficheiro
    public static SensorRecord fromLine(String line) {
        String[] parts = line.trim().split(";");
        if (parts.length < 4) {
            throw new IllegalArgumentException("Linha inválida: " + line);
        }
        long seq = Long.parseLong(parts[0]);
        String sensor = parts[1];
        double temperatura = Double.parseDouble(parts[2]);
        String instante = parts[3];
        return new SensorRecord(seq, sensor, temperatura, instante);
    }
}