package common;

import java.io.Serializable;
import java.util.BitSet;

/**
 * Implementação de um Filtro de Bloom para verificação eficiente de membros.
 * 
 * <p>Um Filtro de Bloom é uma estrutura de dados probabilística que permite
 * verificar se um elemento pertence a um conjunto com:</p>
 * <ul>
 *   <li><b>Falsos positivos possíveis:</b> pode dizer que um elemento está presente quando não está</li>
 *   <li><b>Falsos negativos IMPOSSÍVEIS:</b> se diz que não está, garantidamente não está</li>
 *   <li><b>Eficiente em espaço:</b> usa apenas bits em vez de armazenar elementos completos</li>
 * </ul>
 * 
 * <p>Ideal para: verificar se uma palavra existe no índice invertido antes de
 * fazer a busca completa no HashMap, economizando operações custosas.</p>
 */
public class BloomFilter implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final BitSet bitSet;
    private final int size;
    private final int numHashFunctions;
    
    /**
     * Cria um novo Filtro de Bloom com parâmetros otimizados.
     * 
     * <p>O tamanho do filtro e número de funções hash são calculados automaticamente
     * usando fórmulas ótimas:</p>
     * <ul>
     *   <li>Tamanho: m = -(n * ln(p)) / (ln(2)^2)</li>
     *   <li>Funções hash: k = (m/n) * ln(2)</li>
     * </ul>
     * 
     * @param expectedElements Número esperado de elementos a serem inseridos
     * @param falsePositiveRate Taxa de falsos positivos desejada (ex: 0.01 = 1%)
     */
    public BloomFilter(int expectedElements, double falsePositiveRate) {
        this.size = (int) Math.ceil(
            -(expectedElements * Math.log(falsePositiveRate)) / (Math.log(2) * Math.log(2))
        );
        
        this.numHashFunctions = (int) Math.ceil(
            (size / (double) expectedElements) * Math.log(2)
        );
        
        this.bitSet = new BitSet(size);
        
        System.out.println("[BloomFilter] Criado com " + size + " bits e " + 
                          numHashFunctions + " funções hash.");
    }
    
    /**
     * Adiciona um elemento ao filtro de Bloom.
     * Define múltiplos bits correspondentes às posições calculadas pelas funções hash.
     * 
     * @param element O elemento (palavra) a adicionar
     */
    public void add(String element) {
        for (int i = 0; i < numHashFunctions; i++) {
            int hash = hash(element, i);
            bitSet.set(hash);
        }
    }
    
    /**
     * Verifica se um elemento PODE estar no conjunto.
     * 
     * <p><b>Importante:</b> Este método nunca retorna falsos negativos, mas pode
     * retornar falsos positivos. Se retornar false, o elemento definitivamente não está
     * no conjunto. Se retornar true, o elemento pode ou não estar.</p>
     * 
     * @param element O elemento a verificar
     * @return true se o elemento PODE estar presente (verificar no HashMap),
     *         false se DEFINITIVAMENTE NÃO está (pular busca no HashMap)
     */
    public boolean mightContain(String element) {
        for (int i = 0; i < numHashFunctions; i++) {
            int hash = hash(element, i);
            if (!bitSet.get(hash)) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Calcula a posição hash para o elemento usando a i-ésima função hash.
     * Utiliza double hashing: h(i) = (h1 + i*h2) mod m, onde h1 e h2 são
     * derivados do hashCode da String.
     * 
     * @param element O elemento a fazer hash
     * @param i O índice da função hash (0 até numHashFunctions-1)
     * @return A posição no BitSet (sempre positiva)
     */
    private int hash(String element, int i) {
        int h1 = element.hashCode();
        int h2 = h1 >>> 16;
        
        int hash = (h1 + i * h2) % size;
        
        return Math.abs(hash);
    }
    
    /**
     * Retorna o número de bits definidos (cardinalidade do BitSet).
     * Útil para estatísticas e monitorização do filtro.
     * 
     * @return Número de bits com valor 1
     */
    public int getCardinality() {
        return bitSet.cardinality();
    }
    
    /**
     * Calcula a taxa de ocupação do filtro.
     * Valores próximos de 1.0 (100%) indicam que o filtro está saturado
     * e a taxa de falsos positivos aumenta.
     * 
     * @return Taxa de ocupação entre 0.0 e 1.0
     */
    public double getOccupancyRate() {
        return bitSet.cardinality() / (double) size;
    }
    
    /**
     * Retorna estatísticas formatadas sobre o estado atual do filtro.
     * Inclui tamanho, número de funções hash, bits definidos e taxa de ocupação.
     * 
     * @return String formatada com estatísticas do filtro
     */
    public String getStats() {
        return String.format(
            "BloomFilter[size=%d, k=%d, bits_set=%d, occupancy=%.2f%%]",
            size, numHashFunctions, bitSet.cardinality(), getOccupancyRate() * 100
        );
    }
}
