package common;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;

/**
 * Implementação do serviço de registo que permite rebind remoto.
 * Este serviço corre na Máquina #1 (junto com o RMI Registry)
 * e aceita pedidos de registo de objetos remotos.
 */
public class RegistrationServiceImpl extends UnicastRemoteObject implements RegistrationService {
    
    private final Registry registry;
    
    public RegistrationServiceImpl(Registry registry) throws RemoteException {
        super();
        this.registry = registry;
    }
    
    @Override
    public void registerRemoteObject(String name, Remote obj) throws RemoteException {
        try {
            registry.rebind(name, obj);
            System.out.println("[RegistrationService] Objeto registado: " + name);
        } catch (Exception e) {
            System.err.println("[RegistrationService] Erro ao registar " + name + ": " + e.getMessage());
            throw new RemoteException("Falha ao registar objeto: " + name, e);
        }
    }
    
    @Override
    public void unregisterRemoteObject(String name) throws RemoteException {
        try {
            registry.unbind(name);
            System.out.println("[RegistrationService] Objeto removido: " + name);
        } catch (Exception e) {
            System.err.println("[RegistrationService] Erro ao remover " + name + ": " + e.getMessage());
            throw new RemoteException("Falha ao remover objeto: " + name, e);
        }
    }
    
    /**
     * Inicia o serviço de registo.
     * Deve ser executado na Máquina #1 junto com o RMI Registry.
     */
    public static void main(String[] args) {
        try {
            int rmiPort = Config.getRmiPort();
            
            System.out.println("[RegistrationService] Modo de execução: " + Config.getDeploymentMode());
            
            // Obtém ou cria o registry
            Registry registry;
            try {
                registry = LocateRegistry.getRegistry(rmiPort);
                registry.list(); // Testa se existe
                System.out.println("[RegistrationService] Conectado ao RMI Registry existente na porta " + rmiPort);
            } catch (Exception e) {
                registry = LocateRegistry.createRegistry(rmiPort);
                System.out.println("[RegistrationService] RMI Registry criado na porta " + rmiPort);
            }
            
            // Cria e regista o serviço
            RegistrationServiceImpl service = new RegistrationServiceImpl(registry);
            registry.rebind("RegistrationService", service);
            
            System.out.println("[RegistrationService] Serviço iniciado e pronto para aceitar registos remotos.");
            System.out.println("[RegistrationService] Componentes remotos podem registar-se através deste serviço.");
            
            // Mantém o serviço vivo
            Object keepAlive = new Object();
            synchronized (keepAlive) {
                keepAlive.wait();
            }
            
        } catch (Exception e) {
            System.err.println("[RegistrationService] Erro: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
