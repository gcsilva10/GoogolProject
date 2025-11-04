# Projeto Googol - Motor de Busca Distribuído

**Sistemas Distribuídos 2024/2025**

Este documento serve como relatório completo do projeto Googol, um motor de busca distribuído implementado com Java RMI.

---

## Índice

1. [Arquitetura de Software](#1-arquitetura-de-software)
2. [Componente de Replicação do Índice - Reliable Multicast](#2-componente-de-replicação-do-índice---reliable-multicast)
3. [Componente RPC/RMI](#3-componente-rpcrmi)
4. [Distribuição de Tarefas](#4-distribuição-de-tarefas)
5. [Testes Realizados](#5-testes-realizados)
6. [Compilação e Execução](#6-compilação-e-execução)
7. [Documentação Javadoc](#7-documentação-javadoc)

---

## 1. Arquitetura de Software

### 1.1 Visão Geral

O sistema Googol é composto por quatro componentes principais que comunicam via Java RMI:

- **Gateway**: Ponto central de coordenação do sistema
- **Storage Barrels**: Armazenam o índice invertido e respondem a pesquisas
- **Downloaders**: Fazem crawling de páginas web e enviam dados para os Barrels
- **Client**: Interface do utilizador para interagir com o sistema

### 1.2 Estrutura de Objetos

#### 1.2.1 Gateway (gateway.Gateway)

**Responsabilidades:**

- Gerir fila de URLs para indexação (padrão produtor-consumidor)
- Distribuir pesquisas pelos Barrels usando round-robin com failover
- Agregar estatísticas do sistema
- Notificar clientes via callbacks quando estatísticas mudam
- Registar URLs indexados em ficheiro de log

**Estruturas de Dados Principais:**

- `ConcurrentLinkedQueue<String> urlQueue`: Fila thread-safe de URLs pendentes para crawling
- `Set<String> visitedURLs`: URLs já processados (ConcurrentHashMap.newKeySet() - thread-safe)
- `CopyOnWriteArrayList<BarrelInterface> barrels`: Lista thread-safe de Barrels ativos
- `AtomicInteger nextBarrelIndex`: Índice atómico para round-robin
- `ConcurrentHashMap<String, AtomicInteger> topSearches`: Contadores thread-safe de pesquisas
- `ConcurrentHashMap<String, Long> barrelResponseTimes`: Tempos de resposta dos Barrels
- `ConcurrentHashMap<String, AtomicInteger> barrelSearchCounts`: Contagem de pesquisas por Barrel
- `CopyOnWriteArrayList<StatisticsCallback> statisticsCallbacks`: Lista thread-safe de callbacks

**Thread-safety:**
Todas as estruturas de dados são concorrentes (ConcurrentHashMap, ConcurrentLinkedQueue, CopyOnWriteArrayList) garantindo operações atómicas. Sincronização explícita apenas para escrita de logs (logLock).

#### 1.2.2 Storage Barrel (barrel.Barrel)

**Responsabilidades:**

- Armazenar índice invertido: palavra → conjunto de URLs
- Armazenar backlinks: URL → conjunto de URLs que apontam para ele
- Armazenar informações de páginas (título, citação)
- Responder a pesquisas calculando interseção de conjuntos
- Persistir estado (apenas Barrel primário)
- Sincronizar dados com outros Barrels via RMI

**Estruturas de Dados Principais:**

- `ConcurrentHashMap<String, Set<String>> invertedIndex`: Índice invertido thread-safe (palavra → URLs)
- `ConcurrentHashMap<String, Set<String>> backlinks`: Mapa thread-safe de backlinks (URL → URLs que apontam)
- `ConcurrentHashMap<String, SearchResult> pageInfo`: Informações thread-safe das páginas
- `BloomFilter bloomFilter`: Filtro de Bloom para otimização de pesquisas
- `ConcurrentLinkedQueue<String> urlQueueBackup`: Backup thread-safe da fila de URLs da Gateway
- `Set<String> visitedURLsBackup`: Backup thread-safe de URLs visitados (ConcurrentHashMap.newKeySet())

**Thread-safety:**
Todas as estruturas usam ConcurrentHashMap e ConcurrentLinkedQueue. Operações como computeIfAbsent() garantem atomicidade na atualização do índice.

**Sistema de Persistência Híbrido:**

- **Barrel Primário (índice 0)**: Guarda estado em ficheiro a cada 60 segundos + sincroniza via RMI
- **Barrels Secundários**: Apenas sincronizam via RMI (não guardam em ficheiro)

**Estratégia de Recuperação (ao reiniciar):**

1. Tentar sincronizar via RMI com outros Barrels ativos (prioritário)
2. Se RMI falhar, carregar de ficheiro barrel_state_primary.ser
3. Se ficheiro não existir, começar com índice vazio

#### 1.2.3 Downloader (downloader.Downloader)

**Responsabilidades:**

- Obter URLs da fila da Gateway
- Fazer download e parsing de páginas web (usando Jsoup)
- Extrair palavras, links, título e citação
- Enviar dados para TODOS os Barrels (Reliable Multicast)
- Reportar novos URLs descobertos à Gateway
- Gerir updates pendentes em caso de falha de Barrel

**Estruturas de Dados Principais:**

- `GatewayInterface gateway`: Referência RMI para Gateway
- `List<BarrelInterface> barrels`: Lista de Barrels ativos (com synchronized para thread-safety)
- `ConcurrentHashMap<String, List<PendingUpdate>> pendingUpdates`: Mapa thread-safe de updates pendentes por Barrel

**Thread-safety:**
Usa ConcurrentHashMap para pendingUpdates e blocos synchronized ao manipular a lista de Barrels (conexão/reconexão).

**Algoritmo de Processamento:**

```
1. Obter URL da Gateway
2. Download da página via HTTP (Jsoup)
3. Extrair título, texto, links
4. Tokenizar texto em palavras (lowercase, trim)
5. Extrair citação (primeiras 30 palavras)
6. Enviar dados para TODOS os Barrels (Reliable Multicast)
7. Reportar novos links à Gateway
8. Repetir
```

#### 1.2.4 Client (client.Client)

**Responsabilidades:**

- Apresentar menu interativo ao utilizador
- Realizar pesquisas através da Gateway
- Indexar novos URLs
- Consultar backlinks
- Ver estatísticas em tempo real via callbacks

**Funcionalidades:**

1. Pesquisar: Busca com múltiplos termos
2. Indexar novo URL: Submete URL para crawling
3. Ver backlinks: Lista páginas que apontam para um URL
4. Ver estatísticas: Recebe atualizações automáticas via push

### 1.3 Organização do Código

```
src/
├── barrel/
│   └── Barrel.java              # Implementação do Storage Barrel
├── gateway/
│   └── Gateway.java             # Implementação da Gateway
├── downloader/
│   └── Downloader.java          # Implementação do Downloader
├── client/
│   └── Client.java              # Implementação do Cliente
└── common/
    ├── BarrelInterface.java     # Interface RMI dos Barrels
    ├── GatewayInterface.java    # Interface RMI da Gateway
    ├── SearchResult.java        # Classe de dados para resultados
    ├── StatisticsCallback.java  # Interface de callback para estatísticas
    ├── BloomFilter.java         # Filtro de Bloom para otimização
    ├── Config.java              # Carregamento de configurações
    ├── RegistrationService.java # Interface de registo remoto
    └── RegistrationServiceImpl.java # Implementação de registo remoto
```

### 1.4 Comunicação Entre Componentes

#### Diagrama de Comunicação:

```
Client ←--RMI--→ Gateway ←--RMI--→ Barrels
                    ↓
                  RMI (getURLToCrawl)
                    ↓
               Downloader --RMI (updateIndex)-→ Barrels (TODOS)
                    ↑
                    └─ HTTP (Jsoup) → Web Pages
```

#### Fluxos de Comunicação Principais:

**1. Indexação de URL:**

```
Client.indexNewURL(url)
  → Gateway.indexNewURL(url)
    → Gateway adiciona à urlQueue
    → Downloader.getURLToCrawl()
      → Gateway.getURLToCrawl() (retorna URL)
        → Downloader faz download (HTTP)
          → Downloader.updateIndex() para cada Barrel
            → Barrel.updateIndex() (atualiza índice)
```

**2. Pesquisa:**

```
Client.search(query)
  → Gateway.search(query)
    → Gateway.getNextBarrel() (round-robin)
      → Barrel.search(terms)
        → Barrel procura no índice invertido
          → Retorna List<SearchResult>
            → Gateway ordena por relevância
              → Client apresenta resultados
```

**3. Estatísticas (Push via Callbacks):**

```
Client.registerStatisticsCallback(callback)
  → Gateway.registerStatisticsCallback(callback)
    → Gateway adiciona à lista de callbacks
      → [Evento: nova pesquisa ou indexação]
        → Gateway.notifyStatisticsChange()
          → Para cada callback: callback.onStatisticsUpdate(stats)
            → Client.onStatisticsUpdate(stats)
              → Client apresenta estatísticas atualizadas
```

**4. Reliable Multicast (Downloader → Barrels):**

```
Downloader processa URL
  → Para cada Barrel na lista:
      → Tenta barrel.updateIndex(dados)
        → Se sucesso: continua
        → Se falha: guarda em pendingUpdates[barrelName]
  → Periodicamente tenta reconectar a Barrels falhados
    → Se reconecta: envia todos os pendingUpdates
```

### 1.5 Configuração Centralizada

O ficheiro config.properties centraliza todas as configurações do sistema:

```properties
# RMI Configuration
rmi.port=1099
rmi.host=localhost

# Gateway
gateway.name=GoogolGateway

# Storage Barrels
barrels.count=2                      # Número de Barrels
barrels.prefix=GoogolBarrel          # Prefixo dos nomes

# Bloom Filter
bloom.expected.elements=10000
bloom.false.positive.rate=0.01

# Barrel Auto-save
barrel.autosave.interval=60          # segundos

# Statistics
statistics.monitor.interval=3000     # milissegundos
```

A classe Config.java carrega estas configurações e fornece métodos estáticos para acesso thread-safe.

---

## 2. Componente de Replicação do Índice - Reliable Multicast

### 2.1 Objetivo

Garantir que TODOS os Storage Barrels recebem as mesmas atualizações de índice, mantendo consistência dos dados mesmo em caso de falhas temporárias de Barrels.

### 2.2 Algoritmo de Reliable Multicast

O algoritmo implementado é um **Reliable Multicast baseado em retransmissão com armazenamento de updates pendentes**.

#### 2.2.1 Descrição do Algoritmo

**Fase 1: Envio Multicast**

Quando um Downloader processa uma página:

```java
// 1. Processa a página
String url = processedURL;
Set<String> words = extractedWords;
Set<String> links = extractedLinks;

// 2. Tenta enviar para TODOS os Barrels
for (BarrelInterface barrel : barrels) {
    try {
        barrel.updateIndex(url, title, citation, words, links);
        // Sucesso: Barrel recebeu a atualização
    } catch (RemoteException e) {
        // Falha: Guarda update pendente para retry
        String barrelName = getBarrelName(barrel);
        pendingUpdates.get(barrelName).add(
            new PendingUpdate(url, title, citation, words, links)
        );
    }
}
```

**Fase 2: Reconexão e Retransmissão**

Periodicamente (a cada tentativa de processamento de URL):

```java
// Tenta reconectar a Barrels falhados
for (String barrelName : barrelNames) {
    if (!isConnected(barrelName)) {
        try {
            BarrelInterface barrel = connectToBarrel(barrelName);
            barrels.add(barrel);
  
            // Reenvia TODOS os updates pendentes
            List<PendingUpdate> pending = pendingUpdates.get(barrelName);
            for (PendingUpdate update : pending) {
                barrel.updateIndex(
                    update.url, 
                    update.title, 
                    update.citation,
                    update.words, 
                    update.newLinksOnPage
                );
            }
  
            // Limpa updates pendentes
            pending.clear();
  
        } catch (RemoteException e) {
            // Ainda não conseguiu reconectar
        }
    }
}
```

#### 2.2.2 Estruturas de Dados do Reliable Multicast

**Classe PendingUpdate (inner class em Downloader):**

```java
private static class PendingUpdate {
    final String url;
    final String title;
    final String citation;
    final Set<String> words;
    final Set<String> newLinksOnPage;
    final long timestamp;  // Para debugging
}
```

**Mapa de Updates Pendentes:**

```java
// Mapa thread-safe: BarrelName → Lista de updates que falharam
ConcurrentHashMap<String, List<PendingUpdate>> pendingUpdates = new ConcurrentHashMap<>();
```

**Nota:** ConcurrentHashMap garante thread-safety no acesso ao mapa, mas a manipulação das listas internas de PendingUpdate é feita dentro de blocos synchronized para garantir atomicidade nas operações de adicionar/remover updates.

#### 2.2.3 Propriedades Garantidas

1. **Eventual Consistency**: Todos os Barrels eventualmente recebem todos os updates
2. **No Message Loss**: Updates nunca são perdidos (guardados em pendingUpdates)
3. **Order Preservation**: Updates para o mesmo Barrel são enviados na ordem FIFO
4. **Idempotência**: Método updateIndex() nos Barrels é idempotente (usar Set em vez de List)

### 2.3 Sincronização de Estado Entre Barrels

Além do Reliable Multicast dos Downloaders, os Barrels sincronizam entre si quando reiniciam:

**Métodos RMI para Sincronização:**

```java
// BarrelInterface.java
Map<String, Set<String>> getInvertedIndex() throws RemoteException;
Map<String, Set<String>> getBacklinksMap() throws RemoteException;
Map<String, SearchResult> getPageInfoMap() throws RemoteException;
```

**Processo de Sincronização (em Barrel.java):**

```java
private void syncFromOtherBarrels() {
    for (String barrelName : otherBarrelNames) {
        try {
            BarrelInterface otherBarrel = connectToBarrel(barrelName);
  
            // Copia todos os dados
            this.invertedIndex.putAll(otherBarrel.getInvertedIndex());
            this.backlinks.putAll(otherBarrel.getBacklinksMap());
            this.pageInfo.putAll(otherBarrel.getPageInfoMap());
  
            // Reconstrói Bloom Filter
            rebuildBloomFilter();
  
            System.out.println("Sincronização RMI bem-sucedida com " + barrelName);
            return;  // Sucesso
  
        } catch (RemoteException e) {
            // Tenta próximo Barrel
        }
    }
  
    // Se RMI falhou, tenta carregar de ficheiro
    loadFromFile();
}
```

### 2.4 Tolerância a Falhas

**Cenário 1: Barrel temporariamente offline**

- Downloader guarda updates em pendingUpdates
- Quando Barrel volta, Downloader reenvia automaticamente
- Resultado: Barrel fica sincronizado

**Cenário 2: Barrel reinicia**

- Barrel tenta sincronizar via RMI com outros Barrels
- Se conseguir, obtém estado completo e atualizado
- Se falhar, carrega de ficheiro (Barrel primário)
- Resultado: Recuperação rápida

**Cenário 3: Downloader falha**

- Outros Downloaders continuam a processar URLs
- Quando Downloader reinicia, continua de onde parou
- Resultado: Sistema continua funcional

**Cenário 4: Gateway falha**

- URL queue está guardada nos Barrels (backup)
- Quando Gateway reinicia, recupera queue dos Barrels
- Resultado: Nenhum URL perdido

---

## 3. Componente RPC/RMI

### 3.1 Interfaces RMI

#### 3.1.1 GatewayInterface

Interface principal que o Cliente usa para comunicar com o sistema.

```java
public interface GatewayInterface extends Remote {
  
    // Indexação
    void indexNewURL(String url) throws RemoteException;
    String getURLToCrawl() throws RemoteException;
  
    // Pesquisa
    List<SearchResult> search(String query) throws RemoteException;
    List<String> getBacklinks(String url) throws RemoteException;
  
    // Estatísticas
    String getStatistics() throws RemoteException;
    void registerStatisticsCallback(StatisticsCallback callback) throws RemoteException;
    void unregisterStatisticsCallback(StatisticsCallback callback) throws RemoteException;
}
```

**Métodos Detalhados:**

**void indexNewURL(String url)**

- **Propósito**: Submeter novo URL para indexação
- **Parâmetros**: URL completo (ex: "https://www.uc.pt")
- **Fluxo**:
  1. Verifica se URL já foi visitado (deduplicação)
  2. Se não, adiciona a urlQueue e visitedURLs
  3. Envia backup da queue para Barrels (tolerância a falhas)
  4. Notifica estatísticas mudaram
- **Thread-safety**: Sincronizado em urlQueue

**List `<SearchResult>` search(String query)**

- **Propósito**: Realizar pesquisa por termos
- **Parâmetros**: String com termos separados por espaços
- **Fluxo**:
  1. Tokeniza query em array de termos (lowercase)
  2. Seleciona próximo Barrel (round-robin)
  3. Chama barrel.search(terms)
  4. Se falhar, tenta próximo Barrel (failover)
  5. Ordena resultados por relevância (backlinks)
  6. Atualiza estatísticas de pesquisa
  7. Notifica callbacks
- **Failover**: Tenta todos os Barrels até obter resposta
- **Retorno**: Lista de SearchResult ordenada por relevância

**String getURLToCrawl()**

- **Propósito**: Fornecer URL para Downloader processar (produtor-consumidor)
- **Fluxo**:
  1. Remove URL de urlQueue (poll)
  2. Retorna URL ou null se vazia
- **Thread-safety**: ConcurrentLinkedQueue garante atomicidade

**void registerStatisticsCallback(StatisticsCallback callback)**

- **Propósito**: Registar callback para receber atualizações push
- **Fluxo**:
  1. Adiciona callback a statisticsCallbacks
  2. Envia estatísticas atuais imediatamente
- **Padrão**: Observer pattern com callbacks RMI

#### 3.1.2 BarrelInterface

Interface que define operações dos Storage Barrels.

```java
public interface BarrelInterface extends Remote {
  
    // Pesquisa
    List<SearchResult> search(String[] terms) throws RemoteException;
    List<String> getBacklinks(String url) throws RemoteException;
  
    // Indexação (Reliable Multicast)
    void updateIndex(String url, String title, String citation, 
                     Set<String> words, Set<String> newLinksOnPage) 
                     throws RemoteException;
  
    // Sincronização entre Barrels
    Map<String, Set<String>> getInvertedIndex() throws RemoteException;
    Map<String, Set<String>> getBacklinksMap() throws RemoteException;
    Map<String, SearchResult> getPageInfoMap() throws RemoteException;
  
    // Backup da Gateway
    void backupURLQueue(Queue<String> urlQueue, Set<String> visitedURLs) 
                        throws RemoteException;
    Object[] restoreURLQueue() throws RemoteException;
  
    // Estatísticas
    String getBarrelStats() throws RemoteException;
}
```

**Métodos Detalhados:**

**List `<SearchResult>` search(String[] terms)**

- **Propósito**: Procurar páginas que contêm TODOS os termos
- **Algoritmo**:
  1. Para cada termo, verifica Bloom Filter
     - Se bloomFilter.mightContain(term) == false: retorna lista vazia (otimização)
  2. Obtém conjuntos de URLs para cada termo do índice invertido
  3. Calcula interseção dos conjuntos (páginas com TODOS os termos)
  4. Para cada URL na interseção:
     - Obtém informações da página (título, citação)
     - Calcula relevância = número de backlinks
  5. Ordena por relevância (decrescente)
- **Complexidade**: O(n * m) onde n = número de termos, m = tamanho médio dos conjuntos
- **Otimização**: Bloom Filter elimina pesquisas desnecessárias

**void updateIndex(...)**

- **Propósito**: Atualizar índice com dados de página processada (Reliable Multicast)
- **Fluxo**:
  1. Para cada palavra em words:
     - Adiciona URL ao conjunto da palavra no índice invertido
     - Adiciona palavra ao Bloom Filter
  2. Para cada link em newLinksOnPage:
     - Adiciona URL atual aos backlinks do link
  3. Guarda informações da página (título, citação)
- **Idempotência**: Usar Sets garante que chamadas duplicadas não causam problemas
- **Thread-safety**: ConcurrentHashMap permite updates concorrentes

**Map<String, Set `<String>`> getInvertedIndex()**

- **Propósito**: Obter índice completo para sincronização
- **Uso**: Quando um Barrel reinicia e precisa recuperar estado
- **Retorno**: Cópia do mapa completo

#### 3.1.3 StatisticsCallback

Interface de callback para notificações push.

```java
public interface StatisticsCallback extends Remote {
    void onStatisticsUpdate(String statistics) throws RemoteException;
}
```

**void onStatisticsUpdate(String statistics)**

- **Propósito**: Receber notificação quando estatísticas mudam
- **Chamador**: Gateway (automaticamente quando deteta mudança)
- **Padrão**: Push em vez de polling
- **Vantagem**: Cliente recebe atualizações instantâneas sem overhead

### 3.2 Implementação de Failover

#### 3.2.1 Failover em Pesquisas (Gateway)

A Gateway implementa failover automático usando round-robin com retry:

```java
public List<SearchResult> search(String query) throws RemoteException {
    String[] terms = query.toLowerCase().split("\\s+");
  
    int attempts = 0;
    int maxAttempts = barrels.size();  // Tenta todos os Barrels
  
    while (attempts < maxAttempts) {
        BarrelInterface barrel = getNextBarrel();  // Round-robin
  
        if (barrel == null) {
            throw new RemoteException("Nenhum Barrel disponível");
        }
  
        try {
            List<SearchResult> results = barrel.search(terms);
            // Sucesso: atualiza métricas e retorna
            updateBarrelMetrics(barrel, startTime);
            return results;
  
        } catch (RemoteException e) {
            // Falha: remove Barrel da lista e tenta próximo
            barrels.remove(barrel);
            attempts++;
        }
    }
  
    throw new RemoteException("Todos os Barrels falharam");
}
```

**Estratégia Round-Robin:**

```java
private BarrelInterface getNextBarrel() {
    if (barrels.isEmpty()) {
        connectToBarrels();  // Tenta reconectar
    }
  
    if (barrels.isEmpty()) {
        return null;
    }
  
    int index = nextBarrelIndex.getAndIncrement() % barrels.size();
    return barrels.get(index);
}
```

#### 3.2.2 Reconexão Automática

A Gateway tenta reconectar a Barrels periodicamente:

```java
private void connectToBarrels() {
    barrels.clear();
  
    for (String barrelName : barrelNames) {
        try {
            Registry registry = LocateRegistry.getRegistry(host, port);
            BarrelInterface barrel = (BarrelInterface) registry.lookup(barrelName);
            barrels.add(barrel);
            System.out.println("Conectado a " + barrelName);
        } catch (Exception e) {
            System.err.println("Falha ao conectar a " + barrelName);
        }
    }
}
```

### 3.3 Sistema de Callbacks para Estatísticas

#### 3.3.1 Registo de Callbacks

Cliente regista callback na Gateway:

```java
// Client.java
StatisticsCallback callback = new StatisticsCallbackImpl();
gateway.registerStatisticsCallback(callback);
```

#### 3.3.2 Monitorização e Notificação

Gateway tem thread dedicada que monitoriza mudanças:

```java
private void startStatisticsMonitoringThread() {
    Thread monitor = new Thread(() -> {
        while (true) {
            try {
                Thread.sleep(monitorInterval);  // 3 segundos
  
                String currentStats = buildStatistics();
  
                if (!currentStats.equals(lastStatistics)) {
                    lastStatistics = currentStats;
                    notifyStatisticsCallbacks(currentStats);
                }
  
            } catch (InterruptedException e) {
                break;
            }
        }
    });
    monitor.setDaemon(true);
    monitor.start();
}
```

#### 3.3.3 Notificação de Callbacks

```java
private void notifyStatisticsCallbacks(String statistics) {
    List<StatisticsCallback> toRemove = new ArrayList<>();
  
    for (StatisticsCallback callback : statisticsCallbacks) {
        try {
            callback.onStatisticsUpdate(statistics);
        } catch (RemoteException e) {
            // Cliente desconectou: remove callback
            toRemove.add(callback);
        }
    }
  
    statisticsCallbacks.removeAll(toRemove);
}
```

### 3.4 Tratamento de Exceções RMI

Todas as operações RMI tratam RemoteException:

1. **Em pesquisas**: Failover para próximo Barrel
2. **Em indexação**: Guarda update pendente (Reliable Multicast)
3. **Em callbacks**: Remove callback da lista
4. **Em sincronização**: Tenta próximo Barrel ou carrega de ficheiro

---

## 4. Distribuição de Tarefas

### 4.1 Divisão Adotada

O projeto foi desenvolvido seguindo a primeira divisão proposta no enunciado:

**Elemento 1: Francisco Santos**

- Downloaders (implementação completa)
- Componente multicast fiável dos Barrels (Reliable Multicast)
- Algoritmo de retransmissão de updates pendentes
- Classe PendingUpdate e gestão de reconexões
- Parsing de páginas web com Jsoup
- Extração de palavras, links, título e citação

**Elemento 2: Gonçalo Silva**

- Gateway (implementação completa)
- Componente RPC/RMI dos Barrels
- Interfaces RMI (GatewayInterface, BarrelInterface, StatisticsCallback)
- Sistema de callbacks para estatísticas (push notifications)
- Implementação de failover e round-robin
- Sincronização de estado entre Barrels via RMI

### 4.2 Componentes Partilhados

Ambos os elementos trabalharam em conjunto em:

- **Barrel.java**:

  - Elemento 1: Métodos de receção de updates (updateIndex)
  - Elemento 2: Métodos de pesquisa e sincronização RMI
- **Client.java**:

  - Elemento 1: Menu e interação com utilizador
  - Elemento 2: Implementação de callbacks e comunicação RMI
- **Configuração e Documentação**:

  - Config.java: Ambos contribuíram
  - README.md: Documentação conjunta
  - Javadoc: Documentação de código por cada elemento nas suas componentes
- **Classes Auxiliares**:

  - SearchResult.java: Definição conjunta
  - BloomFilter.java: Implementação e integração conjunta

### 4.3 Responsabilidades Específicas

#### 4.3.1 Elemento 1 - Downloaders e Reliable Multicast

**Ficheiros Principais:**

- downloader/Downloader.java (393 linhas)
- barrel/Barrel.java - métodos updateIndex(), backupURLQueue(), restoreURLQueue()

**Funcionalidades Implementadas:**

1. Download de páginas web via HTTP (Jsoup)
2. Parsing de HTML (título, texto, links)
3. Tokenização de texto em palavras
4. Extração de citação (primeiras 30 palavras)
5. Reliable Multicast: envio para todos os Barrels
6. Gestão de updates pendentes (PendingUpdate)
7. Reconexão automática a Barrels falhados
8. Retransmissão de updates pendentes após reconexão

**Desafios Técnicos:**

- Garantir que todos os Barrels recebem updates (consistência)
- Lidar com falhas temporárias de rede/Barrels
- Gerir memória de updates pendentes (possível crescimento)
- Sincronização thread-safe de lista de Barrels

#### 4.3.2 Elemento 2 - Gateway e RPC/RMI

**Ficheiros Principais:**

- gateway/Gateway.java (680 linhas)
- barrel/Barrel.java - métodos search(), getInvertedIndex(), getBacklinksMap(), getPageInfoMap()
- common/BarrelInterface.java
- common/GatewayInterface.java
- common/StatisticsCallback.java

**Funcionalidades Implementadas:**

1. Gestão de fila de URLs (produtor-consumidor)
2. Distribuição de pesquisas (round-robin)
3. Failover automático em caso de falha de Barrel
4. Sistema de callbacks para estatísticas (push)
5. Agregação de estatísticas de múltiplos Barrels
6. Sincronização de estado entre Barrels via RMI
7. Métodos de obtenção de dados completos para sincronização
8. Tratamento de exceções RMI e reconexão

**Desafios Técnicos:**

- Implementar failover transparente para o Cliente
- Garantir consistência de round-robin com lista dinâmica
- Sistema de callbacks bidirecional via RMI
- Detecção de mudanças em estatísticas (evitar notificações desnecessárias)
- Sincronização eficiente de grandes volumes de dados

---

## 5. Testes Realizados

### 5.1 Tabela de Testes Principais

Esta tabela apresenta os testes mais críticos realizados, focando em funcionalidades essenciais e tolerância a falhas de cada componente.

| #  | Categoria                       | Descrição do Teste                            | Resultado | Observações                                      |
| -- | ------------------------------- | ----------------------------------------------- | --------- | -------------------------------------------------- |
| 1  | Funcionalidade Básica          | Indexar e pesquisar URLs com múltiplos termos  | PASS      | Sistema funciona corretamente end-to-end           |
| 2  | Falha Gateway                   | Reiniciar Gateway durante operação            | PASS      | Queue recuperada dos Barrels via backup            |
| 3  | Falha Barrel Primário          | Desligar Barrel 0 e religá-lo                  | PASS      | Recupera estado via RMI de Barrel secundário      |
| 4  | Falha Barrel Secundário        | Desligar Barrel 1 durante pesquisa              | PASS      | Gateway usa failover automático (round-robin)     |
| 5  | Falha Todos Barrels             | Desligar todos Barrels durante pesquisa         | PASS      | Gateway retorna erro apropriado ao Cliente         |
| 6  | Falha Downloader                | Desligar Downloader durante processamento       | PASS      | Outros Downloaders continuam a funcionar           |
| 7  | Reliable Multicast              | Processar URLs com Barrel offline               | PASS      | Updates guardados em pendingUpdates                |
| 8  | Reliable Multicast - Reconexão | Religar Barrel após falha                      | PASS      | Todos updates pendentes retransmitidos com sucesso |
| 9  | Persistência Barrel            | Reiniciar Barrel 0 com todos offline            | PASS      | Carrega estado de ficheiro .ser                    |
| 10 | Sincronização RMI             | Iniciar Barrel secundário após indexação    | PASS      | Sincroniza índice completo via RMI                |
| 11 | Callbacks                       | Cliente desconecta com callback registado       | PASS      | Gateway remove callback automaticamente            |
| 12 | Concorrência                   | Múltiplos Downloaders e pesquisas simultâneas | PASS      | Estruturas concorrentes garantem thread-safety     |
| 13 | Deduplicação                  | Indexar mesmo URL múltiplas vezes              | PASS      | Processado apenas uma vez                          |
| 14 | Bloom Filter                    | Pesquisar termo definitivamente inexistente     | PASS      | Otimização funciona (não consulta HashMap)      |
| 15 | Carga                           | Indexar 100 URLs consecutivos                   | PASS      | Sistema mantém-se estável e responsivo           |

### 5.2 Metodologia de Testes

**Testes de Funcionalidade (1):**

- Executados manualmente via Cliente
- Verificação de fluxo completo (indexação → pesquisa → resultados)

**Testes de Falhas de Componentes (2-11):**

- Simulação de falhas via Ctrl+C em terminais de componentes específicos
- Verificação de recuperação automática (Reliable Multicast, Failover, Sincronização RMI)
- Análise de mensagens de erro e reconexão em logs
- Validação de persistência e backup de estado

**Testes de Concorrência e Thread-Safety (12):**

- Múltiplas instâncias de componentes executando simultaneamente
- Verificação de ausência de race conditions e deadlocks
- Validação de estruturas concorrentes (ConcurrentHashMap, ConcurrentLinkedQueue)

**Testes de Otimização e Carga (13-15):**

- Deduplicação de URLs
- Eficácia do Bloom Filter
- Teste de carga com volume significativo de dados

### 5.3 Ferramentas Utilizadas

- **Testes Manuais**: Cliente interativo para validação de funcionalidades
- **Scripts Shell**: Automação de cenários de falha
- **Logs**: Sistema extensivo de logging para análise de comportamento
- **JConsole**: Monitorização de threads e memória durante testes de carga

### 5.4 Resultados Gerais

- **Taxa de Sucesso**: 15/15 (100%)
- **Componentes Testados**: Gateway, Barrels (primário e secundários), Downloaders, Cliente
- **Cenários de Falha Validados**: Todos os componentes testados em cenários de crash e recuperação

**Principais Funcionalidades Validadas:**

1. Reliable Multicast com retransmissão de updates pendentes
2. Failover automático com round-robin entre Barrels
3. Persistência e sincronização de estado via RMI e ficheiro
4. Sistema de callbacks bidirecional
5. Thread-safety de todas as estruturas concorrentes

---

## 6. Compilação e Execução

### 6.1 Dependências

- **Java JDK** 11 ou superior
- **Jsoup** 1.21.2 (incluído em lib/jsoup-1.21.2.jar)

### 6.2 Compilação

**Usando Makefile (recomendado):**

```bash
make
```

**Manualmente:**

```bash
mkdir -p bin
javac -d bin -cp "lib/jsoup-1.21.2.jar:." $(find src -name "*.java")
```

### 6.3 Execução

**Configuração Local (uma máquina):**

Editar config.properties:

```properties
rmi.host=localhost
barrels.count=2
```

Executar script automático:

```bash
./run.sh
```

O script abre automaticamente todos os terminais necessários.

**Execução Manual:**

Terminal 1 - RMI Registry:

```bash
rmiregistry -J-Djava.rmi.server.codebase=file:bin/ 1099
```

Terminal 2 - Barrel 0:

```bash
java -Djava.security.policy=security.policy -cp "bin:lib/jsoup-1.21.2.jar" barrel.Barrel 0
```

Terminal 3 - Barrel 1:

```bash
java -Djava.security.policy=security.policy -cp "bin:lib/jsoup-1.21.2.jar" barrel.Barrel 1
```

Terminal 4 - Gateway:

```bash
java -Djava.security.policy=security.policy -cp "bin:lib/jsoup-1.21.2.jar" gateway.Gateway
```

Terminal 5 - Downloader:

```bash
java -Djava.security.policy=security.policy -cp "bin:lib/jsoup-1.21.2.jar" downloader.Downloader
```

Terminal 6 - Client:

```bash
java -Djava.security.policy=security.policy -cp "bin" client.Client
```

### 6.4 Configuração Distribuída (duas máquinas)

Editar config.properties:

```properties
# IP da Máquina #1 (Gateway + Barrel0 + Downloader + RMI Registry)
machine1.ip=IP

# IP da Máquina #2 (Barrel1 + Downloader + Cliente)
machine2.ip=IP
```

Executar script automático:

```bash
# No PC 1
./run_machine1

# No PC 2
./run_machine2
```

O script abre automaticamente todos os terminais necessários.

---

## 7. Documentação Javadoc

### 7.1 Geração de Javadoc

Todo o código está extensivamente documentado com Javadoc. Para gerar a documentação HTML:

```bash
javadoc -d docs -sourcepath src -classpath "lib/jsoup-1.21.2.jar:bin" \
        -subpackages barrel:gateway:downloader:client:common \
        -encoding UTF-8 -charset UTF-8
```

A documentação será gerada em docs/ e pode ser visualizada abrindo docs/index.html num browser.

### 7.2 Estrutura da Documentação Javadoc

A documentação Javadoc inclui:

**Pacotes:**

- barrel - Storage Barrels e persistência
- gateway - Gateway e coordenação
- downloader - Downloaders e crawling
- client - Interface de utilizador
- common - Interfaces RMI e classes partilhadas

**Para cada classe:**

- Descrição detalhada de responsabilidades
- Estruturas de dados principais
- Thread-safety e concorrência
- Exemplos de uso
- Todos os métodos documentados com:
  - Propósito
  - Parâmetros
  - Valor de retorno
  - Exceções lançadas
  - Fluxo de execução

### 7.3 Visualização

Abrir em browser:

```bash
open docs/index.html          # macOS
xdg-open docs/index.html      # Linux
start docs/index.html         # Windows
```

---

## 8. Estrutura de Ficheiros

```
GoogolProject/
├── config.properties             # Configuração centralizada
├── security.policy               # Política de segurança RMI
├── Makefile                      # Compilação automática
├── run.sh                        # Script de execução local
├── run_machine1.sh               # Script para máquina 1 (distribuído)
├── run_machine2.sh               # Script para máquina 2 (distribuído)
├── README.md                     # Este ficheiro
├── lib/
│   └── jsoup-1.21.2.jar         # Biblioteca para parsing HTML
├── src/
│   ├── barrel/
│   │   └── Barrel.java          # Implementação do Storage Barrel
│   ├── gateway/
│   │   └── Gateway.java         # Implementação da Gateway
│   ├── downloader/
│   │   └── Downloader.java      # Implementação do Downloader
│   ├── client/
│   │   └── Client.java          # Implementação do Cliente
│   └── common/
│       ├── BarrelInterface.java          # Interface RMI dos Barrels
│       ├── GatewayInterface.java         # Interface RMI da Gateway
│       ├── SearchResult.java             # Classe de resultados
│       ├── StatisticsCallback.java       # Interface de callback
│       ├── BloomFilter.java              # Filtro de Bloom
│       ├── Config.java                   # Carregamento de configurações
│       ├── RegistrationService.java      # Interface de registo remoto
│       └── RegistrationServiceImpl.java  # Implementação de registo
├── bin/                          # Ficheiros compilados (.class)
├── docs/                         # Documentação Javadoc gerada
├── logs/                         # Logs do sistema
├── barrel_state_primary.ser      # Persistência do Barrel primário
├── barrel_urlqueue_backup.ser    # Backup da URL queue
└── indexed_urls.log              # Log de URLs indexados
```

---

Autores: Gonçalo Silva e Francisco Santos

**Última Atualização**: 2 de Novembro de 2025
