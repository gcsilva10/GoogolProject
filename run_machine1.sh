#!/bin/bash
# Script de execução para Máquina #1
# Corre: 1 downloader, 1 storage barrel (Barrel0) e a Gateway

echo "=================================================="
echo "  GOOGOL - Configuração Máquina #1"
echo "=================================================="
echo "Esta máquina vai executar:"
echo "  - 1 Gateway"
echo "  - 1 Storage Barrel (GoogolBarrel0)"
echo "  - 1 Downloader"
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

# 1. Terminal: Registration Service (cria RMI Registry e aceita registos remotos)
echo "1️⃣  Abrir Terminal: Registration Service + RMI Registry"
osascript -e "tell app \"Terminal\" to do script \"echo '===== MÁQUINA #1: Registration Service ====='; cd '$(pwd)'; java -Djava.security.policy=security.policy -cp $CP common.RegistrationServiceImpl\""
sleep 3

# 2. Terminal: Gateway
echo "2️⃣  Abrir Terminal: Gateway"
osascript -e "tell app \"Terminal\" to do script \"echo '===== MÁQUINA #1: Gateway ====='; cd '$(pwd)'; java -Djava.security.policy=security.policy -cp $CP gateway.Gateway\""
sleep 2

# 3. Terminal: Barrel 0
echo "3️⃣  Abrir Terminal: Storage Barrel 0"
osascript -e "tell app \"Terminal\" to do script \"echo '===== MÁQUINA #1: Barrel 0 ====='; cd '$(pwd)'; java -Djava.security.policy=security.policy -cp $CP barrel.Barrel 0\""
sleep 2

# 4. Terminal: Downloader
echo "4️⃣  Abrir Terminal: Downloader"
osascript -e "tell app \"Terminal\" to do script \"echo '===== MÁQUINA #1: Downloader ====='; cd '$(pwd)'; java -Djava.security.policy=security.policy -cp $CP downloader.Downloader\""

sleep 2

# 5. Terminal: Spring Boot Web Application
echo "5️⃣  Abrir Terminal: Spring Boot Web Application"
osascript -e "tell app \"Terminal\" to do script \"echo '===== MÁQUINA #1: Spring Boot Web ====='; cd '$(pwd)/googol-web'; ./mvnw spring-boot:run\""

echo ""
echo "=================================================="
echo "✅ Todos os terminais da Máquina #1 foram abertos!"
echo "=================================================="
echo ""
echo "Componentes iniciados:"
echo "  ✅ Registration Service + RMI Registry"
echo "  ✅ Gateway"
echo "  ✅ Storage Barrel 0"
echo "  ✅ Downloader"
echo "  ✅ Spring Boot Web Application"
echo ""
echo "⚠️  IMPORTANTE: O Registration Service permite que"
echo "    componentes remotos (Máquina #2) se registem no RMI Registry."
echo ""
echo "🌐 A aplicação web estará disponível em: http://localhost:8080"
echo ""
echo "Para parar todos os serviços, feche os terminais ou use:"
echo "  pkill -f 'RegistrationServiceImpl'"
echo "  pkill -f 'gateway.Gateway'"
echo "  pkill -f 'barrel.Barrel'"
echo "  pkill -f 'downloader.Downloader'"
echo "  pkill -f 'spring-boot:run'"
echo ""
