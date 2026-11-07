-- =====================================================================
-- NEO - Oracle introspection kit
-- =====================================================================
-- Purpose : discover the REAL columns, keys and joins behind each graph
--           node/relationship, so pipelines.json is grounded in facts
--           instead of guesses.
--
-- Who     : run as the user configured in application.properties
--           (db.username). ALL_* works for any object you can see;
--           DBA_* needs DBA (e.g. SYSTEM, which this project uses) and is
--           commented alongside. Pick whichever you have.
--
-- How     : paste a section at a time into SQL Developer / SQLcl / DBeaver.
--           Sections are independent. Read the "WHAT IT TELLS YOU" line.
--
-- Tables of interest (from the current selectors):
--   TBAADM : GAM, SMT, SOL, GSM, HTD, DTD, CTD_DTD_ACLI_VIEW
--   CRMUSER: ACCOUNTS, DEMOGRAPHIC
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. COLUMNS of the core tables
--    WHAT IT TELLS YOU: every column, type and nullability - the source
--    of truth for 'map' and for which columns can be join keys.
-- ---------------------------------------------------------------------
SELECT owner, table_name, column_id, column_name,
       data_type, data_length, data_precision, data_scale, nullable
FROM   all_tab_columns
WHERE  (owner, table_name) IN (
         ('TBAADM','GAM'),
         ('TBAADM','SMT'),
         ('TBAADM','SOL'),
         ('TBAADM','GSM'),
         ('TBAADM','HTD'),
         ('TBAADM','DTD'),
         ('TBAADM','CTD_DTD_ACLI_VIEW'),
         ('CRMUSER','ACCOUNTS'),
         ('CRMUSER','DEMOGRAPHIC')
       )
ORDER  BY owner, table_name, column_id;


-- ---------------------------------------------------------------------
-- 2. PRIMARY KEYS / UNIQUE KEYS
--    WHAT IT TELLS YOU: the true identity of each table, and whether it
--    is single-column or COMPOSITE (e.g. tran_id + tran_date).
-- ---------------------------------------------------------------------
SELECT c.owner, c.table_name, c.constraint_name, c.constraint_type,
       cc.column_name, cc.position
FROM   all_constraints c
JOIN   all_cons_columns cc
       ON  cc.owner = c.owner AND cc.constraint_name = c.constraint_name
WHERE  c.constraint_type IN ('P','U')
AND    c.owner IN ('TBAADM','CRMUSER')
ORDER  BY c.table_name, c.constraint_name, cc.position;


-- ---------------------------------------------------------------------
-- 3. FOREIGN KEYS (the joins, straight from the dictionary)
--    WHAT IT TELLS YOU: which column joins to which table/column - the
--    relationship 'from'/'to' keys. Usually empty for Finacle core (it
--    often enforces joins in app code), which is exactly what we need to
--    know before assuming a join.
-- ---------------------------------------------------------------------
SELECT c.table_name, c.constraint_name, cc.column_name AS fk_column,
       c.r_owner, r.table_name AS ref_table, rcc.column_name AS ref_column,
       cc.position
FROM   all_constraints c
JOIN   all_cons_columns cc
       ON  cc.owner = c.owner AND cc.constraint_name = c.constraint_name
JOIN   all_constraints r
       ON  r.owner = c.r_owner AND r.constraint_name = c.r_constraint_name
JOIN   all_cons_columns rcc
       ON  rcc.owner = r.owner AND rcc.constraint_name = r.constraint_name
       AND rcc.position = cc.position
WHERE  c.constraint_type = 'R'
AND    c.owner IN ('TBAADM','CRMUSER')
ORDER  BY c.table_name, c.constraint_name, cc.position;


-- ---------------------------------------------------------------------
-- 4. INDEXES (and whether they are unique)
--    WHAT IT TELLS YOU: columns that are effectively identifiers even
--    when there is no declared PK - useful for Composite/NODE keys.
-- ---------------------------------------------------------------------
SELECT i.table_owner, i.table_name, i.index_name, i.uniqueness,
       ic.column_position, ic.column_name
FROM   all_indexes i
JOIN   all_ind_columns ic
       ON  ic.index_owner = i.owner AND ic.index_name = i.index_name
WHERE  i.table_owner IN ('TBAADM','CRMUSER')
ORDER  BY i.table_name, i.index_name, ic.column_position;


-- ---------------------------------------------------------------------
-- 5. DISCOVER related tables by name pattern
--    WHAT IT TELLS YOU: sibling tables (D=debit/history chains, address,
--    demographic, device, etc.) you may need for the other graph nodes.
-- ---------------------------------------------------------------------
SELECT owner, table_name, num_rows, last_analyzed
FROM   all_tables
WHERE  owner IN ('TBAADM','CRMUSER')
AND    (   table_name LIKE 'GAM%'
        OR table_name LIKE 'CTD%'
        OR table_name LIKE 'HTD%'
        OR table_name LIKE 'DTD%'
        OR table_name LIKE 'ACCT%'
        OR table_name LIKE 'DEMOG%' )
ORDER  BY owner, table_name;


-- ---------------------------------------------------------------------
-- 6. "WHICH TABLES HAVE COLUMN X?"  (the key discovery query)
--    WHAT IT TELLS YOU: where FORACID / ACID / CIF_ID / TRAN_ID /
--    TRAN_DATE / ORGKEY actually live, so identity + joins are exact.
-- ---------------------------------------------------------------------
SELECT owner, table_name, column_name, data_type
FROM   all_tab_columns
WHERE  column_name IN ('FORACID','ACID','CIF_ID','TRAN_ID','TRAN_DATE',
                       'ORGKEY','SOL_ID','SCHM_CODE','SCHM_TYPE',
                       'ACCT_CRNCY_CODE','TRN_REF_NO')
AND    owner IN ('TBAADM','CRMUSER')
ORDER  BY column_name, owner, table_name;

-- prompt version (SQLcl / SQL Developer substitution variable):
-- SELECT owner, table_name, column_name, data_type
-- FROM   all_tab_columns
-- WHERE  column_name = UPPER('&col')
-- AND    owner IN ('TBAADM','CRMUSER')
-- ORDER  BY owner, table_name;


-- ---------------------------------------------------------------------
-- 7. TABLE / COLUMN COMMENTS
--    WHAT IT TELLS YOU: vendor descriptions that disambiguate columns
--    like foracid vs acid, or the transaction destination field.
-- ---------------------------------------------------------------------
SELECT table_name, comments
FROM   all_tab_comments
WHERE  owner IN ('TBAADM','CRMUSER')
AND    comments IS NOT NULL;

SELECT table_name, column_name, comments
FROM   all_col_comments
WHERE  owner IN ('TBAADM','CRMUSER')
AND    comments IS NOT NULL
ORDER  BY table_name, column_name;


-- ---------------------------------------------------------------------
-- 8. IS IT A VIEW? GET ITS DEFINITION
--    WHAT IT TELLS YOU: what CTD_DTD_ACLI_VIEW really is (union of
--    HTD/DTD?) and which columns it exposes.
-- ---------------------------------------------------------------------
SELECT owner, view_name FROM all_views
WHERE  view_name LIKE 'CTD%' OR view_name LIKE 'ACLI%';

-- full DDL of the view (run the line you need):
-- SELECT DBMS_METADATA.GET_DDL('VIEW','CTD_DTD_ACLI_VIEW','TBAADM') FROM dual;


-- ---------------------------------------------------------------------
-- 9. LIVE ROW COUNTS (sanity: how big is each source)
-- ---------------------------------------------------------------------
SELECT 'TBAADM.GAM'  AS src, COUNT(*) AS n FROM tbaadm.gam
UNION ALL SELECT 'TBAADM.HTD',        COUNT(*) FROM tbaadm.htd
UNION ALL SELECT 'TBAADM.DTD',        COUNT(*) FROM tbaadm.dtd
UNION ALL SELECT 'CRMUSER.ACCOUNTS',  COUNT(*) FROM crmuser.accounts;


-- ---------------------------------------------------------------------
-- 10. IS THE TRANSACTION KEY REALLY COMPOSITE?
--     WHAT IT TELLS YOU: if distinct(tran_id) < total rows, then
--     tran_id alone is NOT unique -> identity must be tran_id+tran_date
--     (or a synthesised id). Run against whichever table holds the txns.
-- ---------------------------------------------------------------------
SELECT COUNT(*)                       AS total_rows,
       COUNT(DISTINCT tran_id)        AS distinct_tran_id,
       COUNT(DISTINCT tran_id||'|'||TO_CHAR(tran_date,'YYYYMMDDHH24MISS'))
                                      AS distinct_tran_id_date
FROM   tbaadm.htd;

-- same idea for the account identity (acid should be unique in GAM):
SELECT COUNT(*) AS total_rows, COUNT(DISTINCT acid) AS distinct_acid,
       COUNT(DISTINCT foracid) AS distinct_foracid
FROM   tbaadm.gam;


-- ---------------------------------------------------------------------
-- 11. NULL-KEY CHECK  (feeds the null-key guard / MERGE keys)
--     WHAT IT TELLS YOU: how many candidate-key rows would be skipped by
--     the null guard - e.g. accounts with no orgkey (the cifId bug).
-- ---------------------------------------------------------------------
SELECT COUNT(*) AS accounts_without_orgkey
FROM   crmuser.accounts
WHERE  orgkey IS NULL;
