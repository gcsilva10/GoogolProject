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
if ! grep -q "gateway.host=" config.properties; then
    echo "❌ ERRO: config.properties não está configurado!"
    echo "Por favor, configure os IPs no config.properties antes de executar."
    exit 1
fi

# Mostra a configuração atual
echo "📋 Configuração atual (config.properties):"
echo "---------------------------------------------------"
grep "rmi.host=" config.properties
grep "gateway.host=" config.properties
grep "barrel.host=" config.properties
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

# Criar diretório para logs
mkdir -p logs

echo "🚀 A iniciar componentes..."
echo ""

# 1. Iniciar RMI Registry
echo "1️⃣  Iniciar RMI Registry..."
rmiregistry &
RMI_PID=$!
sleep 2
echo "   ✅ RMI Registry iniciado (PID: $RMI_PID)"
echo ""

# 2. Iniciar Gateway
echo "2️⃣  Iniciar Gateway..."
java -cp bin:lib/jsoup-1.21.2.jar gateway.Gateway > logs/gateway_machine1.log 2>&1 &
GATEWAY_PID=$!
sleep 3
echo "   ✅ Gateway iniciada (PID: $GATEWAY_PID)"
echo "   📄 Log: logs/gateway_machine1.log"
echo ""

# 3. Iniciar Storage Barrel 0
echo "3️⃣  Iniciar Storage Barrel 0..."
java -cp bin:lib/jsoup-1.21.2.jar barrel.Barrel 0 > logs/barrel0_machine1.log 2>&1 &
BARREL_PID=$!
sleep 2
echo "   ✅ Barrel 0 iniciado (PID: $BARREL_PID)"
echo "   📄 Log: logs/barrel0_machine1.log"
echo ""

# 4. Iniciar Downloader
echo "4️⃣  Iniciar Downloader..."
java -cp bin:lib/jsoup-1.21.2.jar downloader.Downloader > logs/downloader_machine1.log 2>&1 &
DOWNLOADER_PID=$!
sleep 2
echo "   ✅ Downloader iniciado (PID: $DOWNLOADER_PID)"
echo "   📄 Log: logs/downloader_machine1.log"
echo ""

echo "=================================================="
echo "✅ Todos os componentes da Máquina #1 iniciados!"
echo "=================================================="
echo ""
echo "PIDs dos processos:"
echo "  - RMI Registry:  $RMI_PID"
echo "  - Gateway:       $GATEWAY_PID"
echo "  - Barrel 0:      $BARREL_PID"
echo "  - Downloader:    $DOWNLOADER_PID"
echo ""
echo "Para verificar os logs:"
echo "  tail -f logs/gateway_machine1.log"
echo "  tail -f logs/barrel0_machine1.log"
echo "  tail -f logs/downloader_machine1.log"
echo ""
echo "Para parar todos os processos:"
echo "  kill $RMI_PID $GATEWAY_PID $BARREL_PID $DOWNLOADER_PID"
echo ""
echo "Pressione Ctrl+C para parar todos os serviços..."
echo ""

# Salvar PIDs num ficheiro para facilitar limpeza
echo "$RMI_PID $GATEWAY_PID $BARREL_PID $DOWNLOADER_PID" > .pids_machine1

# Aguardar e limpar ao sair
trap "echo ''; echo 'A parar serviços...'; kill $RMI_PID $GATEWAY_PID $BARREL_PID $DOWNLOADER_PID 2>/dev/null; rm -f .pids_machine1; echo 'Serviços parados.'; exit 0" INT TERM

# Manter o script a correr
wait
