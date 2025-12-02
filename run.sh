#!/bin/bash

# --- Script de arranque para macOS ---

# 1. Encontra o JAR do Jsoup na pasta 'lib'
#    Isto encontra o primeiro ficheiro que corresponda a "jsoup-*.jar"
JSOUP_JAR=$(ls lib/jsoup-*.jar 2>/dev/null | head -n 1)

if [ -z "$JSOUP_JAR" ]; then
    echo "Erro: Ficheiro jsoup-*.jar não encontrado na pasta /lib"
    echo "Por favor, descarrega o Jsoup e coloca-o em /lib"
    exit 1
fi

echo "A usar o JSOUP JAR: $JSOUP_JAR"

# 2. Define o Classpath
#    (O separador : é para macOS/Linux)
CP="bin:$JSOUP_JAR"

# 3. Lê o número de barrels do config.properties
BARREL_COUNT=$(grep "^barrels.count=" config.properties | cut -d'=' -f2)
if [ -z "$BARREL_COUNT" ]; then
    BARREL_COUNT=2  # valor padrão
fi

echo "Número de barrels a iniciar: $BARREL_COUNT"

# 4. Navega para o diretório do script (o raiz do projeto)
#    Isto garante que os comandos correm na pasta correta
cd "$(dirname "$0")"

# --- NOVA SECÇÃO: Compilação ---
echo "A limpar o projeto (make clean)..."
make clean

echo "A compilar o projeto (make)..."
make
# Verifica se a compilação falhou
if [ $? -ne 0 ]; then
    echo "Erro: A compilação (make) falhou. A anular o arranque."
    exit 1
fi
echo "Compilação concluída."
# --- FIM DA NOVA SECÇÃO ---


# 5. Abre terminais dinamicamente
echo "A abrir terminais..."

# Terminal 1: RMI Registry
osascript -e "tell app \"Terminal\" to do script \"echo 'Terminal 1: RMI Registry'; cd '$(pwd)'; rmiregistry -J-cp -J$CP\""
sleep 1 # Dá tempo ao registry para arrancar

# Terminais 2 a N+1: Barrels (baseado em BARREL_COUNT)
for i in $(seq 0 $((BARREL_COUNT - 1))); do
    TERMINAL_NUM=$((i + 2))
    echo "A abrir Terminal $TERMINAL_NUM: Barrel $i"
    osascript -e "tell app \"Terminal\" to do script \"echo 'Terminal $TERMINAL_NUM: Barrel $i'; cd '$(pwd)'; java -Djava.security.policy=security.policy -cp $CP barrel.Barrel $i\""
done

sleep 2 # Dá tempo aos barrels para se registarem

# Terminal Gateway
GATEWAY_TERMINAL=$((BARREL_COUNT + 2))
echo "A abrir Terminal $GATEWAY_TERMINAL: Gateway"
osascript -e "tell app \"Terminal\" to do script \"echo 'Terminal $GATEWAY_TERMINAL: Gateway'; cd '$(pwd)'; java -Djava.security.policy=security.policy -cp $CP gateway.Gateway\""

sleep 3 # Aguarda o Gateway estar pronto antes de iniciar Downloader e Cliente

# Terminal Downloader
DOWNLOADER_TERMINAL=$((BARREL_COUNT + 3))
echo "A abrir Terminal $DOWNLOADER_TERMINAL: Downloader"
osascript -e "tell app \"Terminal\" to do script \"echo 'Terminal $DOWNLOADER_TERMINAL: Downloader'; cd '$(pwd)'; java -Djava.security.policy=security.policy -cp $CP downloader.Downloader\""

sleep 1

# Terminal Cliente
CLIENT_TERMINAL=$((BARREL_COUNT + 4))
echo "A abrir Terminal $CLIENT_TERMINAL: Cliente"
osascript -e "tell app \"Terminal\" to do script \"echo 'Terminal $CLIENT_TERMINAL: Cliente'; cd '$(pwd)'; java -Djava.security.policy=security.policy -cp bin client.Client\""

sleep 2

# Terminal Spring Boot Web Application
WEB_TERMINAL=$((BARREL_COUNT + 5))
echo "A abrir Terminal $WEB_TERMINAL: Spring Boot Web Application"
osascript -e "tell app \"Terminal\" to do script \"echo 'Terminal $WEB_TERMINAL: Spring Boot Web Application'; cd '$(pwd)/googol-web'; ./mvnw spring-boot:run\""

echo "Terminais abertos."