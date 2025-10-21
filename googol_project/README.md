# Projeto Googol (Sistemas Distribuídos)

Este é o esqueleto do projeto Googol.

## Dependências

1. **Java JDK** (versão 11 ou superior)
2. **Jsoup**: Descarregar o JAR de [https://jsoup.org/download](https://jsoup.org/download) e colocar em `lib/jsoup-1.17.2.jar`. (Se o nome do ficheiro for diferente, ajuste os comandos abaixo).

## Compilação

Abra um terminal na pasta raiz do projeto (`googol_project/`).

1. Crie a pasta `bin` (se ainda não existir):

   ```sh
   mkdir bin
   ```
2. Compile todo o código-fonte para a pasta `bin`, incluindo a biblioteca Jsoup no classpath:

   **Linux/macOS:**

   ```sh
   javac -d bin -cp "lib/jsoup-1.17.2.jar:." $(find src -name "*.java")
   ```

   **Windows (Command Prompt):**

   ```sh
   javac -d bin -cp "lib/jsoup-1.17.2.jar;." -Xlint:unchecked @sources.txt
   ```

   (Nota: Crie um ficheiro `sources.txt` com `src\pt\uc\dei\sd\common\*.java src\pt\uc\dei\sd\barrel\*.java src\pt\uc\dei\sd\gateway\*.java src\pt\uc\dei\sd\downloader\*.java src\pt\uc\dei\sd\client\*.java` ou liste todos os ficheiros .java manualmente).

   **Windows (PowerShell):**

   ```sh
   javac -d bin -cp "lib/jsoup-1.17.2.jar;." (Get-ChildItem -Recurse -Filter *.java | ForEach-Object { $_.FullName })
   ```

## Execução

**IMPORTANTE:** Vai precisar de **vários terminais abertos** ao mesmo tempo, todos na pasta raiz do projeto (`googol_project/`).

A ordem de execução é crucial!

**NOTA:** Todos os comandos `java` seguintes precisam de acesso aos ficheiros `.class` (em `bin/`) e à biblioteca (`lib/`).

* **Classpath (Linux/macOS):** `java -cp "bin:lib/jsoup-1.17.2.jar"`
* **Classpath (Windows):** `java -cp "bin;lib/jsoup-1.17.2.jar"`

Vou usar a sintaxe Linux/macOS. Adapte `java -cp "..."` para Windows.

---

### Terminal 1: RMI Registry

O RMI Registry é o "serviço de nomes" onde os servidores se registam.

```sh
# Inicia o RMI Registry na porta 1099
rmiregistry
```
