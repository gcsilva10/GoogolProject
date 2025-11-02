package common;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * Serviço de registo que permite que componentes remotos
 * se registem no RMI Registry através de uma chamada RMI.
 * 
 * Este serviço corre na mesma máquina que o RMI Registry (Máquina #1)
 * e aceita pedidos de registo de componentes remotos (ex: Barrels na Máquina #2).
 */
public interface RegistrationService extends Remote {
    
    /**
     * Regista um objeto remoto no RMI Registry.
     * 
     * @param name O nome RMI para registar
     * @param obj O objeto remoto a registar
     * @throws RemoteException Se houver erro na comunicação RMI
     */
    void registerRemoteObject(String name, Remote obj) throws RemoteException;
    
    /**
     * Remove um objeto do RMI Registry.
     * 
     * @param name O nome RMI a remover
     * @throws RemoteException Se houver erro na comunicação RMI
     */
    void unregisterRemoteObject(String name) throws RemoteException;
}
