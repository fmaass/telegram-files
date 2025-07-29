> **Important Note:** You should backup your data before migrating to a new database system. This guide assumes you have an existing SQLite database and want to migrate to either MySQL or PostgreSQL.

## 🔧 Step-by-Step Guide: Migrate from SQLite to MySQL or PostgreSQL

---

### ✅ Step 1: Prepare `.env` File

Create a `.env` file in the root of your project (same location as your `docker-compose.yaml`).

#### 🔸 For **MySQL**:

```env
DB_TYPE=mysql
DB_HOST=telegram-files-mysql
DB_PORT=3306
DB_USER=mysql
DB_PASSWORD=password
DB_NAME=telegram-files
```

#### 🔹 For **PostgreSQL**:

```env
DB_TYPE=postgres
DB_HOST=telegram-files-postgres
DB_PORT=5432
DB_USER=postgres
DB_PASSWORD=postgres
DB_NAME=telegram-files
```

Also include:

```env
TELEGRAM_API_ID=your_api_id
TELEGRAM_API_HASH=your_api_hash
```

---

### ✅ Step 2: Enable the Database Service in `docker-compose.yaml`

Uncomment the corresponding service block for **MySQL** or **PostgreSQL**.

#### 🔸 Example: Enable MySQL

```yaml
telegram-files-mysql:
  container_name: telegram-files-mysql
  image: mysql:8
  restart: always
  environment:
    MYSQL_ROOT_PASSWORD: ${DB_PASSWORD:-password}
    MYSQL_DATABASE: ${DB_NAME:-telegram-files}
    MYSQL_USER: ${DB_USER:-mysql}
    MYSQL_PASSWORD: ${DB_PASSWORD:-password}
  command:
    --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
  volumes:
    - ./data/db/data:/var/lib/mysql
  healthcheck:
    test: [ "CMD", "mysqladmin", "ping", "-h", "localhost", "-u", "root", "-p$MYSQL_ROOT_PASSWORD" ]
    interval: 10s
    timeout: 5s
    retries: 5
    start_period: 30s
```

#### 🔹 Example: Enable PostgreSQL

```yaml
telegram-files-postgres:
  container_name: telegram-files-postgres
  image: postgres:15-alpine
  restart: always
  environment:
    PGUSER: ${DB_USER:-postgres}
    POSTGRES_PASSWORD: ${DB_PASSWORD:-postgres}
    POSTGRES_DB: ${DB_NAME:-telegram-files}
    PGDATA: /var/lib/postgresql/data/pgdata
  volumes:
    - ./data/db/data:/var/lib/postgresql/data
  healthcheck:
    test: [ 'CMD', 'pg_isready' ]
    interval: 1s
    timeout: 3s
    retries: 30
```

---

### ✅ Step 3: Configure the Application to Use the New Database

In the `telegram-files` service section, uncomment the database environment variables:

```yaml
    environment:
      APP_ENV: "prod"
      APP_ROOT: "/app/data"
      TELEGRAM_API_ID: ${TELEGRAM_API_ID}
      TELEGRAM_API_HASH: ${TELEGRAM_API_HASH}
      DB_TYPE: ${DB_TYPE}
      DB_HOST: ${DB_HOST}
      DB_PORT: ${DB_PORT}
      DB_USER: ${DB_USER}
      DB_PASSWORD: ${DB_PASSWORD}
      DB_NAME: ${DB_NAME}
```

Also uncomment the appropriate `depends_on` block:

#### 🔸 For MySQL:

```yaml
    depends_on:
      telegram-files-mysql:
        condition: service_healthy
```

#### 🔹 For PostgreSQL:

```yaml
    depends_on:
      telegram-files-postgres:
        condition: service_healthy
```

---

### ✅ Step 4: Start the Containers

Run:

```bash
docker-compose up -d
```

Wait for the database container and `telegram-files` container to become healthy. The application will automatically create the necessary tables in the new database.

---

### ✅ Step 5: Export Data from SQLite

Make sure you have `sqlite3` installed on your host system (not inside a container).

Then run:

```bash
sqlite3 ./data/database.sqlite .dump > sqlite_dump.sql
```

✅ Since your schema is already compatible with MySQL/PostgreSQL, **no modification of the SQL file is needed**.

---

### ✅ Step 6: Import the Dump into MySQL or PostgreSQL

#### 🔸 Import into MySQL

```bash
docker exec -i telegram-files-mysql mysql -u root -ppassword telegram-files < sqlite_dump.sql
```

#### 🔹 Import into PostgreSQL

First, copy the dump file into the container:

```bash
docker cp sqlite_dump.sql telegram-files-postgres:/tmp/sqlite_dump.sql
```

Then execute:

```bash
docker exec -it telegram-files-postgres psql -U postgres -d telegram-files -f /tmp/sqlite_dump.sql
```

---

### ✅ Step 7: Verify the Data

You can verify that the data was successfully imported by connecting to the database container and checking row counts.

#### Example: For PostgreSQL

```bash
docker exec -it telegram-files-postgres psql -U postgres -d telegram-files
```

```sql
SELECT COUNT(*) FROM file_record;
SELECT COUNT(*) FROM setting_record;
```

#### Example: For MySQL

```bash
docker exec -it telegram-files-mysql mysql -u root -ppassword telegram-files
```

```sql
SELECT COUNT(*) FROM file_record;
SELECT COUNT(*) FROM setting_record;
```

---

## ✅ Done!

Your application is now running on **MySQL or PostgreSQL** with all the original data from the SQLite database fully migrated.
