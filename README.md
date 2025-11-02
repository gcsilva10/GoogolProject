# Projeto Googol (Sistemas Distribuídos)

Este é o projeto Googol - um motor de busca distribuído implementado com Java RMI.

## Estrutura do Projeto

```
GoogolProject/
├── config.properties          # Configuração centralizada
├── security.policy           # Política de segurança RMI
├── Makefile                  # Compilação automática
├── run.sh                    # Script de execução (macOS)
├── lib/
│   └── jsoup-1.21.2.jar     # Biblioteca para parsing HTML
├── src/
│   ├── barrel/              # Storage Barrels
│   │   └── Barrel.java
│   ├── gateway/             # Gateway (coordenador)
│   │   └── Gateway.java
│   ├── downloader/          # Downloaders (crawlers)
│   │   └── Downloader.java
│   ├── client/              # Cliente
│   │   └── Client.java
│   └── common/              # Interfaces e classes partilhadas
│       ├── BarrelInterface.java
│       ├── GatewayInterface.java
│       ├── SearchResult.java
│       ├── StatisticsCallback.java
│       └── Config.java
└── bin/                     # Ficheiros compilados (.class)
```

## Configuração (config.properties)

Todos os componentes do sistema leem as suas configurações do ficheiro **`config.properties`** na raiz do projeto. Isto elimina a necessidade de passar argumentos na linha de comando.

### Ficheiro config.properties

```properties
# === RMI Configuration ===
rmi.port=1099
rmi.host=localhost

# === Gateway ===
gateway.name=GoogolGateway
gateway.log.file=indexed_urls.log

# === Storage Barrels ===
# Número de barrels a criar (nomeados automaticamente como GoogolBarrel0, GoogolBarrel1, ...)
barrels.count=2
# Prefixo para os nomes dos barrels
barrels.prefix=GoogolBarrel

# === Downloaders ===
downloader.threads=2
downloader.timeout=10000
downloader.max.retries=3
downloader.retry.delay=1000

# === Cliente ===
# (Os componentes usam automaticamente gateway.name e a lista gerada de barrels)

# === Estatísticas ===
# Intervalo (em milissegundos) para a thread de monitorização verificar mudanças
statistics.monitor.interval=3000
```

**Notas:**
- Todas as configurações são centralizadas - não é necessário repetir nomes de barrels ou gateway
- Gateway, Downloader e Client usam automaticamente a lista gerada por `barrels.count`
- Para mudar algo, edita-se apenas em um lugar

### Como Adicionar Mais Barrels

Para aumentar o número de Barrels no sistema, basta:

1. **Editar `config.properties`:**
   ```properties
   barrels.count=3  # ou 4, 5, 6...
   ```

2. **Executar o `run.sh`** - ele abrirá automaticamente o número correto de terminais!

**Exemplo com 4 Barrels:**
- `barrels.count=4` → Cria: `GoogolBarrel0`, `GoogolBarrel1`, `GoogolBarrel2`, `GoogolBarrel3`
- O script `run.sh` abrirá: 1 RMI Registry + 4 Barrels + 1 Gateway + 1 Downloader + 1 Client = **8 terminais**

## Dependências

1. **Java JDK** (versão 11 ou superior)
2. **Jsoup**: Descarregar o JAR de [https://jsoup.org/download](https://jsoup.org/download) e colocar em `lib/jsoup-1.17.2.jar`. (Se o nome do ficheiro for diferente, ajuste os comandos abaixo).

## Compilação

O projeto usa um **Makefile** para compilação automática. Abra um terminal na pasta raiz do projeto.

### Usando Make (Recomendado)

```sh
# Compilar o projeto
make

# Limpar e recompilar
make clean && make
```

### Manualmente (sem Make)

**Linux/macOS:**

```sh
mkdir -p bin
javac -d bin -cp "lib/jsoup-1.21.2.jar:." $(find src -name "*.java")
```

**Windows (PowerShell):**

```powershell
mkdir bin -Force
javac -d bin -cp "lib/jsoup-1.21.2.jar;." (Get-ChildItem -Recurse -Path src -Filter *.java).FullName
```

## Execução

**Script Automático (macOS):**

O ficheiro `run.sh` abre automaticamente todos os terminais necessários baseado no número de barrels configurado em `config.properties`. Basta fazer:

```sh
./run.sh
```

O script irá:
1. Ler `barrels.count` do config.properties
2. Compilar o projeto
3. Abrir 1 terminal para RMI Registry
4. Abrir N terminais para os Barrels (onde N = barrels.count)
5. Abrir 1 terminal para Gateway
6. Abrir 1 terminal para Downloader  
7. Abrir 1 terminal para Cliente

**Exemplo:** Com `barrels.count=2` → Abre 6 terminais total
**Exemplo:** Com `barrels.count=5` → Abre 9 terminais total

**Manual (Qualquer Sistema Operativo):**

Vai precisar de **múltiplos terminais abertos** ao mesmo tempo (número depende de `barrels.count` no config). A ordem de execução é importante!

**Classpath:**
* **Linux/macOS:** `java -cp "bin:lib/jsoup-1.21.2.jar"`
* **Windows:** `java -cp "bin;lib/jsoup-1.21.2.jar"`

**Exemplo com 2 Barrels (padrão):**

---

### Terminal 1: RMI Registry

```sh
rmiregistry -J-cp -J"bin:lib/jsoup-1.21.2.jar"
```

### Terminal 2: Barrel 0 (GoogolBarrel0)

```sh
java -Djava.security.policy=security.policy -cp "bin:lib/jsoup-1.21.2.jar" \
     barrel.Barrel 0
```

### Terminal 3: Barrel 1 (GoogolBarrel1)

```sh
java -Djava.security.policy=security.policy -cp "bin:lib/jsoup-1.21.2.jar" \
     barrel.Barrel 1
```

### Terminal 4: Gateway

```sh
java -Djava.security.policy=security.policy -cp "bin:lib/jsoup-1.21.2.jar" \
     gateway.Gateway
```

### Terminal 5: Downloader

```sh
java -Djava.security.policy=security.policy -cp "bin:lib/jsoup-1.21.2.jar" \
     downloader.Downloader
```

### Terminal 6: Client

```sh
java -Djava.security.policy=security.policy -cp "bin" \
     client.Client
```

## Persistência de Estado e Sincronização entre Barrels

Os **Storage Barrels** implementam um **sistema híbrido de persistência e sincronização**:

### Sistema Híbrido: RMI + Ficheiro

**Barrel Primário (Barrel 0 - primeiro da lista):**
- ✅ Sincroniza via RMI com outros Barrels (como todos)
- ✅ **Guarda automaticamente** o estado num ficheiro a cada 60 segundos (configurável)
- ✅ Cria ficheiro: `barrel_state_primary.ser`
- 🎯 **Propósito**: Backup para recuperação completa do sistema

**Barrels Secundários (Barrel 1, 2, 3...):**
- ✅ Sincronizam via RMI com outros Barrels
- ❌ NÃO guardam em ficheiro (não precisam, o Barrel 0 tem tudo)

### Estratégia de Recuperação (ao reiniciar um Barrel):

1. **Primeira tentativa: RMI** 
   - Tenta conectar-se a outros Barrels ativos
   - Se conseguir, copia todos os dados via RMI
   - ✅ **Mais rápido e dados sempre atualizados**

2. **Segunda tentativa: Ficheiro** (se RMI falhar)
   - Carrega do ficheiro `barrel_state_primary.ser`
   - ✅ **Fallback garantido mesmo se todos os Barrels estiverem offline**

3. **Terceira tentativa: Começar vazio**
   - Se nem RMI nem ficheiro funcionarem
   - Sistema começa a indexar do zero

### Vantagens desta Abordagem:

✅ **Tolerância máxima a falhas**: Mesmo se todos os Barrels falharem, há um backup
✅ **Dados sempre atualizados**: RMI é preferido, ficheiro é só backup
✅ **Eficiência**: Apenas 1 Barrel guarda em disco (menos I/O)
✅ **Recuperação rápida**: Se outro Barrel estiver ativo, sincronização é instantânea
✅ **Backup automático**: A cada 60 segundos (configurável)

### Filtro de Bloom para Otimização de Pesquisas

Cada Barrel usa um **Filtro de Bloom** para otimizar pesquisas:

**O que é um Filtro de Bloom?**
- Estrutura de dados probabilística que usa apenas bits (muito eficiente em espaço)
- Permite verificar rapidamente se uma palavra existe no índice
- **Falsos positivos possíveis** (pode dizer que está quando não está) →  Verifica depois no HashMap
- **Falsos negativos IMPOSSÍVEIS** (se diz que não está, definitivamente não está) → Poupa busca no HashMap

**Como funciona na pesquisa:**
1. Cliente pesquisa por: `"java programming distributed"`
2. **Bloom Filter verifica cada palavra:**
   - "java" → PODE estar (verifica no HashMap)
   - "programming" → PODE estar (verifica no HashMap)
   - "distributed" → **DEFINITIVAMENTE NÃO ESTÁ** ❌
3. **Pesquisa cancelada imediatamente** - poupa tempo de buscar no HashMap!

**Configuração:**
```properties
bloom.expected.elements=10000        # Número esperado de palavras únicas
bloom.false.positive.rate=0.01       # Taxa de falsos positivos (1%)
```

### Como Funciona:

**Inicialização:**
- Quando um Barrel inicia, lê a lista de barrels do `config.properties`
- Identifica qual é ele próprio pelo índice passado como argumento (0, 1, 2...)
- **Se for o Barrel 0**: Inicia thread de auto-save
- Tenta conectar-se aos **outros Barrels da lista** via RMI
- Se falhar, tenta carregar do ficheiro `barrel_state_primary.ser`

**Durante operação:**
- Todos recebem atualizações via Reliable Multicast dos Downloaders
- **Apenas o Barrel 0** guarda estado em ficheiro a cada 60 segundos
- Todos mantêm dados sincronizados em tempo real

**Após crash/reinício:**
1. Barrel tenta RMI primeiro (dados mais recentes)
2. Se RMI falhar, carrega do ficheiro (backup)
3. Se ficheiro não existir, começa vazio

### Configuração:

```properties
# Intervalo de auto-save (apenas para Barrel 0)
barrel.autosave.interval=60  # segundos
```

### Vantagens desta Abordagem:

- ✅ **Tolerância máxima a falhas**: Backup em ficheiro + sincronização RMI
- ✅ **Dados sempre atualizados**: RMI é preferido, ficheiro é fallback
- ✅ **Eficiência**: Apenas 1 Barrel escreve em disco (menos I/O)
- ✅ **Recuperação rápida**: RMI é instantânea se outro Barrel estiver ativo
- ✅ **Backup automático**: Barrel 0 guarda estado a cada 60 segundos
- ✅ **Sem dependência**: Sistema funciona mesmo se todos os Barrels falharem
- ✅ **Configuração centralizada**: Apenas editar `config.properties`

### Exemplo de Uso:

**Cenário 1: Sistema normal (Barrels ativos)**
```sh
# 1. Iniciar Barrel 0 (GoogolBarrel0 - PRIMÁRIO)
java -Djava.security.policy=security.policy -cp "bin:lib/jsoup-1.21.2.jar" \
     barrel.Barrel 0
# Output: [Barrel GoogolBarrel0] Este é o BARREL PRIMÁRIO - guardará estado em ficheiro.
# Output: [Barrel GoogolBarrel0] Thread de auto-save iniciada (intervalo: 60s).

# 2. Iniciar Barrel 1 (GoogolBarrel1)
java -Djava.security.policy=security.policy -cp "bin:lib/jsoup-1.21.2.jar" \
     barrel.Barrel 1
# Output: [Barrel GoogolBarrel1] A tentar sincronizar via RMI...
# Output: [Barrel GoogolBarrel1] Conectado a GoogolBarrel0. A copiar dados...
# Output: [Barrel GoogolBarrel1] Sincronização RMI bem-sucedida.
```

**Cenário 2: Todos Barrels offline (usa ficheiro)**
```sh
java -Djava.security.policy=security.policy -cp "bin:lib/jsoup-1.21.2.jar" \
     barrel.Barrel 1
# Output: [Barrel GoogolBarrel1] Sincronização RMI falhou. A tentar carregar do ficheiro...
# Output: [Barrel GoogolBarrel1] Estado carregado de barrel_state_primary.ser
# ✅ Sistema recuperou do ficheiro!
```

**Cenário 3: Barrel crashou e reinicia**
```sh
java -Djava.security.policy=security.policy -cp "bin:lib/jsoup-1.21.2.jar" \
     barrel.Barrel 1
# Output: [Barrel GoogolBarrel1] Conectado a GoogolBarrel0. A copiar dados...
# ✅ Recuperação instantânea via RMI!
```

### Formato do Comando:

```sh
java ... Barrel <índice>
```

- **`<índice>`**: Posição na lista gerada pelo `barrels.count` do `config.properties` (0, 1, 2...)
  - `0` = primeiro barrel (GoogolBarrel0)
  - `1` = segundo barrel (GoogolBarrel1)
  - `2` = terceiro barrel (GoogolBarrel2)
  - etc.

**Nota:** Já não são criados ficheiros `.ser` locais. Os Barrels sincronizam-se entre si via RMI.

## Funcionalidades do Cliente

### Menu Principal

O cliente oferece as seguintes funcionalidades:

1. **Pesquisar** - Procura páginas que contenham um conjunto de palavras
   - Resultados ordenados por relevância (número de backlinks)
   - Paginação automática de 10 em 10 resultados

2. **Indexar novo URL** - Submete um novo URL para ser indexado
   - O URL é adicionado à fila de processamento
   - Os Downloaders processam automaticamente

3. **Ver backlinks** - Consulta páginas que têm ligação para um URL específico

4. **Ver Estatísticas (TEMPO REAL COM CALLBACKS)** - Visualiza estatísticas do sistema
   - **Usa sistema de Push (callbacks) em vez de polling**
   - **Atualizadas APENAS quando algo muda** (pesquisa, indexação)
   - Cliente regista callback na Gateway
   - Gateway notifica automaticamente todos os clientes registados
   - **Eficiente**: Sem chamadas desnecessárias, sem overhead de polling
   - **Instantâneo**: Vê mudanças no exato momento em que acontecem
   - **Ecrã limpo e reformatado automaticamente**
   - Prima ENTER a qualquer momento para voltar ao menu

### Estatísticas em Tempo Real com Callbacks

Quando escolhe a opção "4. Ver Estatísticas", o sistema usa um mecanismo de **push notifications** via RMI callbacks:

**Como funciona:**

1. **Cliente regista-se na Gateway**: `gateway.registerStatisticsCallback(callback)`
2. **Gateway monitoriza mudanças**: Sempre que há uma pesquisa ou indexação
3. **Gateway notifica automaticamente**: Chama `callback.onStatisticsUpdate()`
4. **Cliente recebe e apresenta**: Atualização instantânea no ecrã

**Vantagens sobre polling:**
- ✅ **Eficiência**: Não há chamadas RMI desnecessárias a cada X segundos
- ✅ **Instantâneo**: Vê a mudança no exato momento em que acontece
- ✅ **Escalável**: Gateway controla quando notificar
- ✅ **Economia de recursos**: Sem overhead de polling constante

```
========================================
  ESTATÍSTICAS DO GOOGOL (PUSH)
========================================
Atualizado: 14:23:15
Atualizações recebidas: 5
(Prima ENTER para voltar ao menu)

== Estatísticas do Googol ==

-- 10 Pesquisas Mais Comuns --
'java programming': 15 pesquisas
'distributed systems': 12 pesquisas
...

-- Barrels Ativos --
[GoogolBarrel1] Índice: 523 palavras, 42 URLs.
[GoogolBarrel2] Índice: 523 palavras, 42 URLs.

-- Tempo Médio de Resposta (décimas de segundo) --
[GoogolBarrel1] Média: 3 (total: 45, pesquisas: 15)
[GoogolBarrel2] Média: 2 (total: 30, pesquisas: 15)

========================================
Aguardando próxima atualização...
```

**Cenário de uso:**
1. Cliente escolhe opção 4
2. Ecrã mostra estatísticas atuais
3. Noutro terminal, alguém faz uma pesquisa
4. **INSTANTANEAMENTE** o ecrã atualiza com a nova pesquisa no top 10!
5. Alguém indexa um URL
6. **INSTANTANEAMENTE** o ecrã atualiza com o novo número de URLs
7. Cliente pressiona ENTER quando quiser sair

As estatísticas são atualizadas **automaticamente e apenas quando há mudanças reais**, sem polling desperdiçado.


