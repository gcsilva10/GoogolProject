#!/bin/bash
# Script de execução para Máquina #2
# Corre: Barrel 1, Downloader, Cliente RMI (Consola) e Web Server (Spring Boot)

echo "=================================================="
echo "  GOOGOL - Configuração Máquina #2"
echo "=================================================="

# 1. Ler IPs do config.properties
MACHINE1_IP=$(grep "^machine1.ip=" config.properties | cut -d'=' -f2 | tr -d '\r')
MACHINE2_IP=$(grep "^machine2.ip=" config.properties | cut -d'=' -f2 | tr -d '\r')

if [ -z "$MACHINE1_IP" ] || [ -z "$MACHINE2_IP" ]; then
    echo "❌ ERRO: Não foi possível ler os IPs do config.properties."
    exit 1
fi

echo "📍 IP da Gateway (Máquina 1): $MACHINE1_IP"
echo "📍 IP desta máquina (Máquina 2): $MACHINE2_IP"
echo "=================================================="
echo ""

# Verifica compilação Backend
if [ ! -d "bin" ]; then
    echo "🔨 A compilar o Backend..."
    make
fi

JSOUP_JAR=$(ls lib/jsoup-*.jar 2>/dev/null | head -n 1)
CP="bin:$JSOUP_JAR"
RMI_OPTS="-Djava.rmi.server.hostname=$MACHINE2_IP -Djava.security.policy=security.policy"
WEB_DIR="googol-web"

cd "$(dirname "$0")"

echo "🚀 A abrir terminais..."

# 1. Barrel 1
echo "1️⃣  Abrir Terminal: Storage Barrel 1"
osascript -e "tell app \"Terminal\" to do script \"echo '===== MÁQUINA #2: Barrel 1 ====='; cd '$(pwd)'; java $RMI_OPTS -cp $CP barrel.Barrel 1\""
sleep 2

# 2. Downloader
echo "2️⃣  Abrir Terminal: Downloader"
osascript -e "tell app \"Terminal\" to do script \"echo '===== MÁQUINA #2: Downloader ====='; cd '$(pwd)'; java $RMI_OPTS -cp $CP downloader.Downloader\""
sleep 2

# 3. Cliente Consola
echo "3️⃣  Abrir Terminal: Cliente RMI (Consola)"
osascript -e "tell app \"Terminal\" to do script \"echo '===== MÁQUINA #2: Cliente Consola ====='; cd '$(pwd)'; java $RMI_OPTS -cp bin client.Client\""
sleep 2

# 4. Web Server (Spring Boot) - COM CONFIGURAÇÃO AUTOMÁTICA
if [ -d "$WEB_DIR" ]; then
    echo "4️⃣  Abrir Terminal: Web Server (Spring Boot)"
    chmod +x "$WEB_DIR/mvnw"
    
    # AQUI ESTÁ A CORREÇÃO:
    # Passamos o IP da Máquina 1 como argumento para o Spring Boot saber onde está a Gateway
    osascript -e "tell app \"Terminal\" to do script \"echo '===== MÁQUINA #2: Web Server ====='; cd '$(pwd)/$WEB_DIR'; ./mvnw spring-boot:run -Dspring-boot.run.jvmArguments='-Dgoogol.gateway.host=$MACHINE1_IP'\""
else
    echo "⚠️  AVISO: Pasta '$WEB_DIR' não encontrada."
fi

echo ""
echo "✅ Todos os terminais iniciados."
echo "🌍 Acede ao site (HTTP, não HTTPS!):"
echo "   👉 http://$MACHINE2_IP:8080"
echo ""