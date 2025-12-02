#!/bin/bash
# Script de execução para Máquina #2
# Corre: 1 downloader, 1 storage barrel (Barrel1) e 1 cliente RMI

echo "=================================================="
echo "  GOOGOL - Configuração Máquina #2"
echo "=================================================="
echo "Esta máquina vai executar:"
echo "  - 1 Storage Barrel (GoogolBarrel1)"
echo "  - 1 Downloader"
echo "  - 1 Cliente RMI (modo interativo)"
echo "=================================================="
echo ""

# Verifica se o config.properties foi configurado
if ! grep -q "machine1.ip=" config.properties; then
    echo "❌ ERRO: config.properties não está configurado!"
    echo "Por favor, configure os IPs no config.properties antes de executar."
    exit 1
fi

# Mostra a configuração atual
echo "📋 Configuração atual (config.properties):"
echo "---------------------------------------------------"
grep "machine1.ip=" config.properties
grep "machine2.ip=" config.properties
echo "---------------------------------------------------"
echo ""

read -p "Pressione ENTER para continuar ou Ctrl+C para cancelar..."
echo ""

# Compilar o projeto
echo "🔨 A compilar o projeto..."
make clean
make
if [ $? -ne 0 ]; then
    echo "❌ Erro na compilação!"
    exit 1
fi
echo "✅ Compilação concluída"
echo ""

# Define o Classpath
JSOUP_JAR=$(ls lib/jsoup-*.jar 2>/dev/null | head -n 1)
if [ -z "$JSOUP_JAR" ]; then
    echo "❌ Erro: Ficheiro jsoup-*.jar não encontrado na pasta /lib"
    exit 1
fi
CP="bin:$JSOUP_JAR"

# Navega para o diretório do script
cd "$(dirname "$0")"

echo "🚀 A abrir terminais para cada componente..."
echo ""

# 1. Terminal: Barrel 1
echo "1️⃣  Abrir Terminal: Storage Barrel 1"
osascript -e "tell app \"Terminal\" to do script \"echo '===== MÁQUINA #2: Barrel 1 ====='; cd '$(pwd)'; java -Djava.security.policy=security.policy -cp $CP barrel.Barrel 1\""
sleep 2

# 2. Terminal: Downloader
echo "2️⃣  Abrir Terminal: Downloader"
osascript -e "tell app \"Terminal\" to do script \"echo '===== MÁQUINA #2: Downloader ====='; cd '$(pwd)'; java -Djava.security.policy=security.policy -cp $CP downloader.Downloader\""
sleep 2

# 3. Terminal: Cliente (interativo)
echo "3️⃣  Abrir Terminal: Cliente RMI"
osascript -e "tell app \"Terminal\" to do script \"echo '===== MÁQUINA #2: Cliente ====='; cd '$(pwd)'; java -Djava.security.policy=security.policy -cp bin client.Client\""

sleep 2

# 4. Terminal: Spring Boot Web Application
echo "4️⃣  Abrir Terminal: Spring Boot Web Application"
osascript -e "tell app \"Terminal\" to do script \"echo '===== MÁQUINA #2: Spring Boot Web ====='; cd '$(pwd)/googol-web'; ./mvnw spring-boot:run\""

echo ""
echo "=================================================="
echo "✅ Todos os terminais da Máquina #2 foram abertos!"
echo "=================================================="
echo ""
echo "Componentes iniciados:"
echo "  ✅ Storage Barrel 1"
echo "  ✅ Downloader"
echo "  ✅ Cliente RMI (interativo)"
echo "  ✅ Spring Boot Web Application"
echo ""
echo "O Cliente está em modo interativo no terceiro terminal."
echo ""
echo "🌐 A aplicação web estará disponível em: http://localhost:8080"
echo ""
echo "Para parar todos os serviços, feche os terminais ou use:"
echo "  pkill -f 'barrel.Barrel'"
echo "  pkill -f 'downloader.Downloader'"
echo "  pkill -f 'client.Client'"
echo "  pkill -f 'spring-boot:run'"
echo ""
