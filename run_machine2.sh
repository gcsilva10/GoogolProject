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

# 1. Iniciar Storage Barrel 1
echo "1️⃣  Iniciar Storage Barrel 1..."
java -cp bin:lib/jsoup-1.21.2.jar barrel.Barrel 1 > logs/barrel1_machine2.log 2>&1 &
BARREL_PID=$!
sleep 3
echo "   ✅ Barrel 1 iniciado (PID: $BARREL_PID)"
echo "   📄 Log: logs/barrel1_machine2.log"
echo ""

# 2. Iniciar Downloader
echo "2️⃣  Iniciar Downloader..."
java -cp bin:lib/jsoup-1.21.2.jar downloader.Downloader > logs/downloader_machine2.log 2>&1 &
DOWNLOADER_PID=$!
sleep 2
echo "   ✅ Downloader iniciado (PID: $DOWNLOADER_PID)"
echo "   📄 Log: logs/downloader_machine2.log"
echo ""

echo "=================================================="
echo "✅ Serviços de background iniciados!"
echo "=================================================="
echo ""
echo "PIDs dos processos:"
echo "  - Barrel 1:      $BARREL_PID"
echo "  - Downloader:    $DOWNLOADER_PID"
echo ""
echo "Para verificar os logs em outra janela:"
echo "  tail -f logs/barrel1_machine2.log"
echo "  tail -f logs/downloader_machine2.log"
echo ""
echo "=================================================="
echo ""

# Salvar PIDs num ficheiro para facilitar limpeza
echo "$BARREL_PID $DOWNLOADER_PID" > .pids_machine2

# 3. Iniciar Cliente (interativo)
echo "3️⃣  A iniciar Cliente interativo..."
echo ""
sleep 1

# Trap para limpar processos ao sair
trap "echo ''; echo 'A parar serviços...'; kill $BARREL_PID $DOWNLOADER_PID 2>/dev/null; rm -f .pids_machine2; echo 'Serviços parados.'; exit 0" INT TERM

# Iniciar cliente (foreground, interativo)
java -cp bin:lib/jsoup-1.21.2.jar client.Client

# Quando o cliente terminar, limpar os outros processos
echo ""
echo "Cliente encerrado. A parar serviços de background..."
kill $BARREL_PID $DOWNLOADER_PID 2>/dev/null
rm -f .pids_machine2
echo "Todos os serviços da Máquina #2 foram parados."
