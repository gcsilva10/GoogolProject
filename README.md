
# Relatório do Projeto Googol - Sistemas Distribuídos 2025/2026

**Autores:**

* Gonçalo Coimbra Pereira da Silva - 2023215512
* Francisco Miguel Aldinhas Seabra Santos - 2023215501

---

## 1. Introdução

Este relatório descreve a implementação do projeto Googol, um motor de pesquisa distribuído capaz de indexar e pesquisar páginas Web. O sistema foi desenvolvido em duas fases, evoluindo de uma arquitetura backend baseada em RMI para um sistema completo com interface Web, integração de APIs externas e funcionalidades em tempo real.

O objetivo principal foi criar uma arquitetura robusta, tolerante a falhas e escalável, garantindo a disponibilidade do serviço mesmo em caso de falha de componentes críticos.

---

## 2. Arquitetura de Software

O sistema segue uma arquitetura Cliente-Servidor distribuída, organizada em camadas bem definidas.

### 2.1. Componentes do Sistema

1. **Gateway (Balanceador de Carga):**
   * Ponto de entrada único para os clientes.
   * Distribui os pedidos de pesquisa entre os Storage Barrels disponíveis (Round-Robin).
   * Gere a fila de URLs a indexar e coordena os Downloaders.
   * Mantém estatísticas globais do sistema.
2. **Storage Barrels (Armazenamento de Dados):**
   * Armazenam o índice invertido e os dados das páginas.
   * Operam em cluster com replicação ativa para garantir tolerância a falhas.
   * Persistem dados em disco para recuperação em caso de reinício.
3. **Downloaders (Workers):**
   * Responsáveis pelo *crawling*. Obtêm URLs da Gateway, descarregam o conteúdo HTML e enviam os dados processados para os Barrels.
4. **Web Server (Spring Boot):**
   * Atua como cliente RMI da Gateway.
   * Disponibiliza uma interface HTTP/HTML aos utilizadores finais e integra serviços externos.

### 2.2. Tecnologias Utilizadas

* **Java RMI:** Comunicação entre Gateway, Barrels e Downloaders.
* **Spring Boot & Thymeleaf:** Servidor Web e motor de templates (MVC).
* **WebSockets (STOMP):** Atualizações de estatísticas em tempo real.
* **Jsoup:** Parsing de HTML nos Downloaders.
* **APIs REST:** Integração com Hacker News e Groq AI.

---

## 3. Implementação do Backend (RPC/RMI)

A comunicação distribuída é gerida através de interfaces remotas partilhadas, que definem o contrato entre os vários componentes.

### 3.1. Definição das Interfaces

Em vez de expor a implementação, o sistema baseia-se nas seguintes abstrações:

#### **GatewayInterface**

Define os métodos que a Gateway disponibiliza aos clientes (Web e Consola):

* `indexNewURL(String url)`: Recebe um URL para ser adicionado à fila de indexação.
* `search(String query)`: Encaminha a pesquisa para um Barrel disponível e retorna os resultados.
* `getBacklinks(String url)`: Consulta quais as páginas que apontam para um determinado link.
* `getStatistics()`: Agrega e retorna dados sobre o estado do sistema.
* `registerStatisticsCallback(...)`: Permite que clientes subscrevam notificações em tempo real.

#### **BarrelInterface**

Define as operações suportadas pelos nós de armazenamento:

* `updateIndex(...)`: Método invocado pelos Downloaders para inserir dados de uma nova página (multicast).
* `search(...)` e `getBacklinks(...)`: Métodos de leitura invocados pela Gateway.
* `backupURLQueue(...)` e `restoreURLQueue()`: Métodos de suporte à tolerância a falhas, permitindo guardar e recuperar o estado da Gateway.

#### **StatisticsCallback**

Interface de *callback* implementada pelo cliente (neste caso, pelo servidor Spring Boot) para receber notificações assíncronas sempre que as estatísticas do sistema são atualizadas.

#### **RegistrationService**

Interface auxiliar que contorna as restrições de segurança do RMI Registry, permitindo o registo remoto de componentes.

* `registerRemoteObject(String name, Remote obj)`: Permite que um componente (ex: Barrel na Máquina 2) se registe no Registry (na Máquina 1).
* `unregisterRemoteObject(String name)`: Remove um registo quando o componente é encerrado.

### 3.2. Mecanismos de Tolerância a Falhas

* **Replicação de Dados:** Os Downloaders enviam os dados indexados para todos os Barrels ativos sequencialmente. Se um falhar, a operação continua nos restantes e guarda o update pendente para reenvio posterior.
* **Recuperação de Estado:** Se a Gateway falhar, ao reiniciar contacta os Barrels para recuperar a fila de URLs pendentes e a lista de URLs visitados, garantindo que o trabalho não é perdido.
* **Failover na Pesquisa:** Se o Barrel selecionado para uma pesquisa não responder, a Gateway tenta automaticamente o próximo Barrel da lista, tornando a falha transparente para o utilizador.

---

## 4. Integração Web e Spring Boot

A camada de apresentação foi modernizada utilizando Spring Boot, seguindo o padrão MVC (Model-View-Controller).

### 4.1. Estrutura MVC

* **Controller:** Gere os pedidos HTTP. O `GoogolController` mapeia rotas como `/search` e `/index` e invoca a lógica de negócio.
* **Service:** O `GoogolService` encapsula a complexidade do RMI. É este componente que se liga à Gateway, gere reconexões e converte os dados RMI para objetos Java utilizáveis pelo Frontend.
* **View:** Páginas HTML renderizadas com Thymeleaf, apresentando dados dinâmicos ao utilizador.

### 4.2. Funcionalidades em Tempo Real

Para evitar o recarregamento constante da página de estatísticas, foi implementada uma solução baseada em WebSockets:

1. O servidor Spring Boot regista-se como observador na Gateway via RMI (`StatisticsCallback`).
2. Quando a Gateway deteta alterações, notifica o servidor Spring Boot.
3. O servidor propaga essa informação para o navegador via WebSocket (`/topic/stats`).
4. O Frontend atualiza os gráficos e tabelas instantaneamente.

---

## 5. Integração com Serviços Externos

O sistema foi enriquecido com duas integrações via API REST.

### 5.1. Hacker News (Indexação Automática)

Foi implementado um serviço que consome a API pública do Hacker News. O sistema:

1. Obtém os IDs das histórias mais populares ("Top Stories").
2. Descarrega os detalhes de cada história.
3. Filtra por termos de pesquisa definidos pelo utilizador.
4. Envia os URLs encontrados diretamente para a Gateway para serem indexados.

### 5.2. Inteligência Artificial (Groq API)

Para fornecer resumos contextualizados das pesquisas:

1. O sistema recolhe os excertos (*snippets*) dos 5 primeiros resultados da pesquisa.
2. Constrói um *prompt* pedindo um resumo conciso em português.
3. Envia o pedido para a API da Groq (modelo `llama-3.1-8b-instant`), que oferece respostas rápidas e compatibilidade com o formato OpenAI.
4. O resumo gerado é apresentado na página de resultados.

---

## 6. Testes e Validação

O sistema foi submetido a diversos cenários de teste para validar a robustez e funcionalidade.

| ID            | Cenário de Teste      | Descrição                                                   |   Resultado   | Observações                                                    |
| :------------ | :--------------------- | :------------------------------------------------------------ | :------------: | :--------------------------------------------------------------- |
| **T01** | Indexação Simples    | Inserir URL manual via Web e verificar presença na pesquisa. | **PASS** | URL aparece na pesquisa e logs.                                  |
| **T02** | Failover de Barrel     | Terminar o Barrel 0 durante uma pesquisa Web.                 | **PASS** | Gateway redireciona para Barrel 1 automaticamente.               |
| **T03** | Recuperação Gateway  | Terminar a Gateway e reiniciar.                               | **PASS** | Gateway recupera a Queue de URLs a partir do backup nos Barrels. |
| **T04** | WebSockets (Real-time) | Indexar URL numa aba e observar estatísticas noutra.         | **PASS** | Gráfico atualiza sem refresh da página.                        |
| **T05** | API Hacker News        | Pesquisar tópico "Java" na ferramenta HN.                    | **PASS** | URLs relevantes são injetados na Gateway.                       |
| **T06** | Integração AI        | Pesquisar termo com múltiplos resultados.                    | **PASS** | Resumo contextual gerado com sucesso pela Groq API.              |
| **T07** | Paginação            | Pesquisar termo muito comum.                                  | **PASS** | Resultados divididos corretamente em páginas de 10.             |
| **T08** | Reliable Multicast     | Desligar Barrel temporariamente durante indexação.          | **PASS** | Downloader reenvia dados quando Barrel reconecta.                |

---

## 7. Distribuição de Tarefas

O desenvolvimento do projeto foi dividido da seguinte forma pelos elementos do grupo:

**Gonçalo Silva:**

* Implementação do núcleo da Gateway e gestão de concorrência.
* Desenvolvimento dos mecanismos RMI (Interfaces e Callbacks).
* Configuração inicial do Spring Boot e integração do `GoogolService` com o Backend.
* Implementação da lógica de WebSockets no Backend.

**Francisco Santos:**

* Implementação dos Downloaders e algoritmo de *Reliable Multicast*.
* Desenvolvimento dos Storage Barrels e persistência de dados.
* Implementação dos Controllers e Views (Thymeleaf/HTML) do servidor Web.
* Integração dos serviços REST (Hacker News e AI Service).

---

## 8. Instruções de Instalação e Execução

O sistema foi configurado para correr em modo distribuído utilizando scripts de automação.

### Pré-requisitos

* Java JDK 17 ou superior.
* Maven (para o módulo Web).
* Conexão de rede entre as máquinas (recomenda-se a mesma sub-rede ou Hotspot para evitar bloqueios de portas).

### Passo 1: Configuração

Edite o ficheiro `config.properties` na raiz do projeto em **ambas as máquinas** com os IPs corretos:

```properties
machine1.ip=<IP_DA_MAQUINA_1>
machine2.ip=<IP_DA_MAQUINA_2>
```

### Passo 2: Execução na Máquina 1 (Backend Principal)

Esta máquina executará o RMI Registry, a Gateway e o Barrel primário.

1. Abra o terminal na raiz do projeto.
2. Execute: `./run_machine1.sh`

### Passo 3: Execução na Máquina 2 (Frontend e Barrel Secundário)

Esta máquina executará o Barrel secundário, o Downloader e o Servidor Web.

1. Abra o terminal na raiz do projeto.
2. Execute: `./run_machine2.sh`

### Passo 4: Acesso

Abra o navegador e aceda ao endereço fornecido pelo script da Máquina 2 (ex: `http://<IP_DA_MAQUINA_2>:8080`).

---

## 9. Conclusão

O projeto Googol permitiu implementar um sistema distribuído complexo, abordando desafios como a consistência de dados, tolerância a falhas e integração de sistemas heterogéneos (Java RMI e Web/REST). A solução final cumpre todos os requisitos funcionais, oferecendo uma experiência de utilizador fluida e uma infraestrutura backend resiliente.
