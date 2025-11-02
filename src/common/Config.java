package common;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Classe utilitária para carregar e aceder à configuração do sistema.
 * Lê o ficheiro config.properties e fornece métodos para obter valores.
 * Utiliza carregamento lazy através de bloco static initializer.
 */
public class Config {
    private static final String CONFIG_FILE = "config.properties";
    private static Properties properties;
    
    static {
        loadConfig();
    }
    
    /**
     * Carrega o ficheiro de configuração para a memória.
     * Caso o ficheiro não exista, utiliza valores padrão definidos nos métodos getters.
     */
    private static void loadConfig() {
        properties = new Properties();
        try (FileInputStream fis = new FileInputStream(CONFIG_FILE)) {
            properties.load(fis);
            System.out.println("[Config] Ficheiro de configuração carregado: " + CONFIG_FILE);
        } catch (IOException e) {
            System.err.println("[Config] AVISO: Não foi possível carregar " + CONFIG_FILE);
            System.err.println("[Config] A usar valores padrão.");
        }
    }
    
    /**
     * Obtém uma propriedade como String.
     * 
     * @param key A chave da propriedade
     * @return O valor da propriedade ou null se não existir
     */
    public static String get(String key) {
        return properties.getProperty(key);
    }
    
    /**
     * Obtém uma propriedade como String com valor padrão.
     * 
     * @param key A chave da propriedade
     * @param defaultValue O valor padrão se a propriedade não existir
     * @return O valor da propriedade ou o valor padrão
     */
    public static String get(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }
    
    /**
     * Obtém uma propriedade como inteiro.
     * 
     * @param key A chave da propriedade
     * @param defaultValue O valor padrão se a propriedade não existir ou for inválida
     * @return O valor da propriedade como int ou o valor padrão
     */
    public static int getInt(String key, int defaultValue) {
        String value = properties.getProperty(key);
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                System.err.println("[Config] Valor inválido para " + key + ": " + value);
            }
        }
        return defaultValue;
    }
    
    /**
     * Obtém uma propriedade como lista de strings separadas por vírgula.
     * 
     * @param key A chave da propriedade
     * @return Lista de strings ou lista vazia se a propriedade não existir
     */
    public static List<String> getList(String key) {
        String value = properties.getProperty(key);
        if (value != null && !value.trim().isEmpty()) {
            return Arrays.asList(value.split("\\s*,\\s*"));
        }
        return List.of();
    }
    
    /**
     * Obtém uma propriedade como long.
     * 
     * @param key A chave da propriedade
     * @param defaultValue O valor padrão se a propriedade não existir ou for inválida
     * @return O valor da propriedade como long ou o valor padrão
     */
    public static long getLong(String key, long defaultValue) {
        String value = properties.getProperty(key);
        if (value != null) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                System.err.println("[Config] Valor inválido para " + key + ": " + value);
            }
        }
        return defaultValue;
    }
    
    /**
     * Obtém o hostname/IP do RMI Registry.
     * O RMI Registry está sempre na Máquina #1 (onde está a Gateway).
     * 
     * @return O hostname configurado (IP da Máquina #1)
     */
    public static String getRmiHost() {
        return getMachine1IP();
    }
    
    /**
     * Obtém a porta do RMI Registry.
     * 
     * @return A porta configurada (padrão: 1099)
     */
    public static int getRmiPort() {
        return getInt("rmi.port", 1099);
    }
    
    /**
     * Obtém o IP da Máquina #1.
     * Máquina #1 corre: Gateway + Barrel0 + Downloader + RMI Registry
     * 
     * @return O IP da Máquina #1 (padrão: "localhost")
     */
    public static String getMachine1IP() {
        return get("machine1.ip", "localhost");
    }
    
    /**
     * Obtém o IP da Máquina #2.
     * Máquina #2 corre: Barrel1 + Downloader + Cliente
     * 
     * @return O IP da Máquina #2 (padrão: "localhost")
     */
    public static String getMachine2IP() {
        return get("machine2.ip", "localhost");
    }
    
    /**
     * Obtém o hostname/IP da máquina onde a Gateway corre.
     * A Gateway está sempre na Máquina #1.
     * 
     * @return O hostname da Gateway (IP da Máquina #1)
     */
    public static String getGatewayHost() {
        return getMachine1IP();
    }
    
    /**
     * Obtém o hostname/IP da máquina onde os Barrels correm.
     * IMPORTANTE: Este método determina automaticamente qual IP usar
     * baseado no índice do Barrel:
     * - Barrel 0 (primário): usa IP da Máquina #1
     * - Barrel 1+: usa IP da Máquina #2
     * 
     * Para uso nos Barrels, chame getBarrelHost(barrelIndex)
     * 
     * @return O hostname padrão (IP da Máquina #1)
     */
    public static String getBarrelHost() {
        return getMachine1IP();
    }
    
    /**
     * Obtém o hostname/IP correto para um Barrel específico.
     * - Barrel 0: Máquina #1
     * - Barrel 1+: Máquina #2
     * 
     * @param barrelIndex O índice do Barrel (0, 1, 2, ...)
     * @return O IP correto para este Barrel
     */
    public static String getBarrelHost(int barrelIndex) {
        if (barrelIndex == 0) {
            return getMachine1IP();
        } else {
            return getMachine2IP();
        }
    }
    
    /**
     * Obtém o nome RMI da Gateway.
     * 
     * @return O nome configurado (padrão: "GoogolGateway")
     */
    public static String getGatewayName() {
        return get("gateway.name", "GoogolGateway");
    }
    
    /**
     * Obtém a lista de nomes RMI dos Barrels.
     * Primeiro tenta ler de barrels.list, senão gera dinamicamente
     * com base em barrels.count e barrels.prefix.
     * 
     * @return Lista de nomes dos Barrels (ex: ["GoogolBarrel0", "GoogolBarrel1"])
     */
    public static List<String> getBarrelsList() {
        int count = getInt("barrels.count", 2);
        String prefix = get("barrels.prefix", "GoogolBarrel");
        
        List<String> barrels = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            barrels.add(prefix + i);
        }
        return barrels;
    }
    
    public static int getDownloaderThreads() {
        return getInt("downloader.threads", 2);
    }
    
    /**
     * Obtém o número esperado de elementos para o Filtro de Bloom.
     * Usado para calcular o tamanho ideal do filtro.
     * 
     * @return Número esperado de elementos (padrão: 10000)
     */
    public static int getBloomExpectedElements() {
        return getInt("bloom.expected.elements", 10000);
    }
    
    /**
     * Obtém a taxa de falsos positivos desejada para o Filtro de Bloom.
     * Valores típicos: 0.01 (1%), 0.001 (0.1%).
     * 
     * @return Taxa de falsos positivos (padrão: 0.01)
     */
    public static double getBloomFalsePositiveRate() {
        String value = get("bloom.false.positive.rate", "0.01");
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            System.err.println("[Config] Valor inválido para bloom.false.positive.rate: " + value);
            return 0.01;
        }
    }
    
    /**
     * Obtém o intervalo de monitorização de estatísticas em milissegundos.
     * Define com que frequência a Gateway verifica mudanças nas estatísticas.
     * 
     * @return Intervalo em milissegundos (padrão: 3000ms = 3 segundos)
     */
    public static long getStatisticsMonitorInterval() {
        return getLong("statistics.monitor.interval", 3000);
    }
    
    /**
     * Obtém o intervalo de auto-save do Barrel primário em milissegundos.
     * Também usado pela Gateway para backup periódico da URL queue.
     * Lê o valor em SEGUNDOS do ficheiro e converte para milissegundos.
     * 
     * @return Intervalo em milissegundos (padrão: 60000ms = 60 segundos)
     */
    public static long getBarrelAutoSaveInterval() {
        int seconds = getInt("barrel.autosave.interval", 60);
        return seconds * 1000L;
    }
}
