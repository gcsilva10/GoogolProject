package common;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * Interface de callback para receber atualizações de estatísticas em tempo real (push).
 * 
 * <p>Os Clientes que pretendem receber notificações automáticas quando as estatísticas
 * mudam devem implementar esta interface e registar-se na Gateway usando
 * {@link GatewayInterface#registerStatisticsCallback(StatisticsCallback)}.</p>
 * 
 * <p>Este padrão implementa o modelo push em vez de polling, reduzindo tráfego de rede
 * e latência na atualização de estatísticas.</p>
 */
public interface StatisticsCallback extends Remote {
    
    /**
     * Método chamado pela Gateway quando as estatísticas são atualizadas.
     * A Gateway detecta mudanças (novas pesquisas, novos URLs indexados, etc.)
     * e notifica automaticamente todos os callbacks registados.
     * 
     * @param statistics String formatada com as estatísticas completas do sistema
     * @throws RemoteException Se houver falha na comunicação RMI
     */
    void onStatisticsUpdate(String statistics) throws RemoteException;
}
