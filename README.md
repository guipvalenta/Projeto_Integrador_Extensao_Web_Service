# Projeto Integrador Extensao Web Service
Projeto de extensão Web service Professor Lucio



# Backend Java

Backend Java que consiste em:

- servidor HTTP na porta 8080
- página estática servida a partir da pasta front/
- endpoint POST /api/oracao para registrar pedidos de oração
- endpoint GET /api/health para verificar funcionamento
- endpoint POST /api/admin/login para login do administrador
- endpoint GET /api/admin/pedidos para listar pedidos
- endpoint POST /api/admin/pedidos/{id}/responder para responder pedidos
- endpoint DELETE /api/admin/pedidos/{id}/excluir para excluir pedidos

## Como executar:

1. Verifique se o **MySQL do XAMPP** está iniciado.

2. **Compile**:
   ```
   javac -cp "back;C:\Users\user\Downloads\mysql-connector-java-5.1.48.jar" back\Backend.java
   ```

3. **Execute**:
   ```
   java -cp "back;C:\Users\user\Downloads\mysql-connector-java-5.1.48.jar" Backend
   ```

4. Abra no navegador:
   ```
   http://localhost:8080/
   ```
