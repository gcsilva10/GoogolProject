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

# 3. Define os 6 comandos (SEM aspas extra a proteger $CP e "bin")
#    vvvvvvv ESTA É A SECÇÃO CORRIGIDA vvvvvvv
CMD1="rmiregistry -J-cp -J$CP"
CMD2="java -Djava.security.policy=security.policy -cp $CP pt.uc.dei.sd.barrel.Barrel GoogolBarrel1"
CMD3="java -Djava.security.policy=security.policy -cp $CP pt.uc.dei.sd.barrel.Barrel GoogolBarrel2"
CMD4="java -Djava.security.policy=security.policy -cp $CP pt.uc.dei.sd.gateway.Gateway GoogolBarrel1 GoogolBarrel2"
CMD5="java -Djava.security.policy=security.policy -cp $CP pt.uc.dei.sd.downloader.Downloader 2 GoogolGateway GoogolBarrel1 GoogolBarrel2"
CMD6="java -Djava.security.policy=security.policy -cp bin pt.uc.dei.sd.client.Client GoogolGateway"
#    ^^^^^^^ ESTA É A SECÇÃO CORRIGIDA ^^^^^^^

# 4. Navega para o diretório do script (o raiz do projeto)
#    Isto garante que os comandos correm na pasta correta
cd "$(dirname "$0")"

# 5. Abre 6 terminais e executa os comandos
echo "A abrir os 6 terminais..."

osascript -e "tell app \"Terminal\" to do script \"echo 'Terminal 1: RMI Registry'; cd '$(pwd)'; $CMD1\""
sleep 1 # Dá 1 segundo ao registry para arrancar
osascript -e "tell app \"Terminal\" to do script \"echo 'Terminal 2: Barrel 1'; cd '$(pwd)'; $CMD2\""
osascript -e "tell app \"Terminal\" to do script \"echo 'Terminal 3: Barrel 2'; cd '$(pwd)'; $CMD3\""
sleep 1 # Dá 1 segundo aos barrels para se registarem
osascript -e "tell app \"Terminal\" to do script \"echo 'Terminal 4: Gateway'; cd '$(pwd)'; $CMD4\""
osascript -e "tell app \"Terminal\" to do script \"echo 'Terminal 5: Downloaders'; cd '$(pwd)'; $CMD5\""
sleep 1
osascript -e "tell app \"Terminal\" to do script \"echo 'Terminal 6: Cliente'; cd '$(pwd)'; $CMD6\""

echo "Terminais abertos."