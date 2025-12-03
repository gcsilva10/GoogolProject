#!/bin/bash
# Script de execução para Máquina #1
# Corre: RegistrationService, Gateway, Barrel0 e Downloader

echo "=================================================="
echo "  GOOGOL - Configuração Máquina #1"
echo "=================================================="

# 1. Obter o IP da Máquina 1 a partir do config.properties
# Isto garante que usamos o mesmo IP que a Máquina 2 está a tentar contactar
MACHINE1_IP=$(grep "^machine1.ip=" config.properties | cut -d'=' -f2 | tr -d '\r')

if [ -z "$MACHINE1_IP" ]; then
    echo "❌ ERRO: Não foi possível ler 'machine1.ip' do config.properties."
    exit 1
fi

echo "📍 IP detetado para RMI: $MACHINE1_IP"
echo "   (Este IP será forçado em todos os componentes)"
echo "=================================================="
echo ""

# Verifica compilação
if [ ! -d "bin" ]; then
    echo "🔨 A compilar o projeto..."
    make
fi

# Define Classpath
JSOUP_JAR=$(ls lib/jsoup-*.jar 2>/dev/null | head -n 1)
CP="bin:$JSOUP_JAR"

# Define a flag RMI que corrige o problema de conexão
# Esta flag obriga o Java a "escutar" e responder no IP público
RMI_OPTS="-Djava.rmi.server.hostname=$MACHINE1_IP -Djava.security.policy=security.policy"

cd "$(dirname "$0")"

echo "🚀 A abrir terminais..."

# 1. Registration Service + RMI Registry
# Importante: Passamos o RMI_OPTS aqui para o Registry ficar acessível de fora
echo "1️⃣  Abrir Terminal: Registration Service"
osascript -e "tell app \"Terminal\" to do script \"echo '===== MÁQUINA #1: Registration Service ====='; cd '$(pwd)'; java $RMI_OPTS -cp $CP common.RegistrationServiceImpl\""
sleep 4 # Espera extra para garantir que o Registry arranca

# 2. Gateway
echo "2️⃣  Abrir Terminal: Gateway"
osascript -e "tell app \"Terminal\" to do script \"echo '===== MÁQUINA #1: Gateway ====='; cd '$(pwd)'; java $RMI_OPTS -cp $CP gateway.Gateway\""
sleep 2

# 3. Barrel 0
echo "3️⃣  Abrir Terminal: Barrel 0"
osascript -e "tell app \"Terminal\" to do script \"echo '===== MÁQUINA #1: Barrel 0 ====='; cd '$(pwd)'; java $RMI_OPTS -cp $CP barrel.Barrel 0\""
sleep 2

# 4. Downloader
echo "4️⃣  Abrir Terminal: Downloader"
osascript -e "tell app \"Terminal\" to do script \"echo '===== MÁQUINA #1: Downloader ====='; cd '$(pwd)'; java $RMI_OPTS -cp $CP downloader.Downloader\""

echo ""
echo "✅ Todos os componentes iniciados no IP: $MACHINE1_IP"
echo "⚠️  IMPORTANTE: Se a Máquina 2 ainda der erro, tens de DESLIGAR A FIREWALL nesta máquina."
echo ""