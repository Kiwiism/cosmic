# Cosmic Server CMS

The Server CMS is a separate operations console for configuration coded in Cosmic.
It does not replace the Database CMS, which remains responsible for game database
records such as drops, shops, accounts, inventories, and gachapon.

## Ports and databases

- Web: `http://localhost:3001`
- API: `http://localhost:8082`
- Database: `cosmic_server_cms`
- Optional private Cosmic bridge: `http://127.0.0.1:8787`

Copy `.env.example` to `.env` and set the MySQL password. The JDBC URL includes
`createDatabaseIfNotExist=true`, so the API creates the database and Liquibase
tables when the MySQL user has permission.

## Start

From PowerShell:

```powershell
.\server-cms\start-server-cms.ps1
```

The first browser visit asks for an owner account. Settings saved in the CMS are
desired state and apply on the next Cosmic restart. If the Server CMS database is
unavailable, Cosmic keeps the original `config.yaml` and Java values.

## Compatibility labels

- `No client/WZ edit`: behavior and available server-side display are complete.
- `WZ edit required`: server behavior changes, but packaged WZ text/data must be
  changed for the native client presentation to match.
- `Client edit required`: networking, UI capacity, or packet behavior also needs
  a client binary/configuration change.
- `WZ + client edit`: both data and client behavior must be updated.

Every setting drawer shows its original source, original key or Java symbol,
implementation files, fallback value, restart behavior, risk, and compatibility.
