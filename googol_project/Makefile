# --- Variáveis de Configuração ---

# O compilador Java
JCC = javac

# Flags do compilador (para debugging e avisos)
JFLAGS = -g -Xlint:unchecked

# Diretório do código-fonte
SRC_DIR = src

# Diretório de saída para os .class
BIN_DIR = bin

# Diretório das bibliotecas
LIB_DIR = lib

# --- Fim da Configuração ---


# --- Lógica do Makefile ---

# Encontra o JAR do Jsoup automaticamente (assume que é o único .jar em lib/)
# Se tiveres um nome específico, podes descomentar a linha abaixo
# JSOUP_JAR = $(LIB_DIR)/jsoup-1.17.2.jar
JSOUP_JAR := $(wildcard $(LIB_DIR)/*.jar)

# Deteta o sistema operativo para usar o separador de classpath correto
# (Windows usa ';' e Linux/macOS usa ':')
ifeq ($(OS),Windows_NT)
    SEP = ;
else
    SEP = :
endif

# Define o Classpath completo
CP = $(JSOUP_JAR)$(SEP)$(BIN_DIR)

# Encontra todos os ficheiros .java recursivamente
SOURCES = $(shell find $(SRC_DIR) -name "*.java")

# --- Alvos (Targets) ---

# O alvo 'all' (ou 'default') é o que corre quando executas 'make'
all: compile

# Alvo para criar o diretório 'bin'
$(BIN_DIR):
	@mkdir -p $(BIN_DIR)

# Alvo de compilação
# Depende que o diretório 'bin' exista
compile: $(BIN_DIR)
	@echo "Compilando todo o código-fonte..."
	@$(JCC) $(JFLAGS) -d $(BIN_DIR) -sourcepath $(SRC_DIR) -cp "$(CP)" $(SOURCES)
	@echo "Compilação concluída."

# Alvo 'clean' para apagar os ficheiros compilados
clean:
	@echo "A limpar o diretório $(BIN_DIR)..."
	@rm -rf $(BIN_DIR)
	@echo "Limpeza concluída."

# Declara os alvos que não são ficheiros
.PHONY: all compile clean