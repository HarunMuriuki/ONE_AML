package one.neo.oracle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Oracle selectors for the 6 graph entities (tbaadm / crmuser tables).
 * Each takes a single bind: max rows. Extend a selector here and its rows
 * flow into GraphModel automatically - nothing else to touch.
 */
public final class OracleSelectors {

    private OracleSelectors() {}

    public static final Map<String, String> SQL = new LinkedHashMap<>();
    public static final Map<String, String> SOURCE = new LinkedHashMap<>();

    static {
        SQL.put("Account", """
            SELECT g.foracid AS accountnum, g.acid AS acid, g.cif_id AS cifid,
                   g.sol_id AS solid, g.schm_code AS schemecode, g.schm_type AS schemetype,
                   g.acct_name AS name, g.acct_short_name AS shortname,
                   g.acct_crncy_code AS currency, g.clr_bal_amt AS balance,
                   g.acct_opn_date AS openeddate, g.acct_ownership AS ownership,
                   g.acct_cls_flg AS closedflag, s.acct_status AS status
            FROM tbaadm.gam g
            LEFT JOIN tbaadm.smt s ON s.bank_id = g.bank_id AND s.acid = g.acid
            WHERE g.bank_id = '01' AND g.del_flg = 'N' AND g.entity_cre_flg = 'Y'
              AND ROWNUM <= ?
            """);
        SOURCE.put("Account", "tbaadm.gam (+ tbaadm.smt)");

        SQL.put("Branch", """
            SELECT sol_id AS solid, sol_desc AS name, bank_id AS bankid
            FROM tbaadm.sol WHERE del_flg = 'N' AND ROWNUM <= ?
            """);
        SOURCE.put("Branch", "tbaadm.sol (fallback: DISTINCT sol_id FROM tbaadm.gam)");

        SQL.put("Customer", """
            SELECT c.orgkey AS cifid, c.cust_first_name AS firstname, c.cust_last_name AS lastname,
                   c.cust_dob AS dob, d.nationality AS nationality, c.occupation AS occupation
            FROM crmuser.accounts c
            LEFT JOIN crmuser.demographic d ON d.orgkey = c.orgkey
            WHERE c.suspended = 'N' AND ROWNUM <= ?
            """);
        SOURCE.put("Customer", "crmuser.accounts (+ crmuser.demographic)");

        SQL.put("SchemeCode", """
            SELECT DISTINCT schm_code AS code, MIN(schm_desc) AS descr
            FROM tbaadm.gsm WHERE del_flg = 'N' GROUP BY schm_code
            FETCH FIRST ? ROWS ONLY
            """);
        SOURCE.put("SchemeCode", "tbaadm.gsm (fallback: DISTINCT schm_code FROM tbaadm.gam)");

        SQL.put("SchemeType", """
            SELECT DISTINCT schm_type AS type, MIN(schm_type_desc) AS descr
            FROM tbaadm.gsm WHERE del_flg = 'N' GROUP BY schm_type
            FETCH FIRST ? ROWS ONLY
            """);
        SOURCE.put("SchemeType", "tbaadm.gsm (fallback: DISTINCT schm_type FROM tbaadm.gam)");

        SQL.put("Transaction", """
            SELECT h.tran_id AS tranid, h.tran_date AS trandate, h.acid AS acid,
                   h.tran_amt AS amount, h.tran_crncy_code AS currency,
                   h.tran_type AS trantype, h.tran_sub_type AS transubtype,
                   h.part_tran_type AS parttrantype, h.tran_particular AS particulars,
                   h.pstd_flg AS postedflag, h.sol_id AS solid,
                   h.tran_rmks AS remarks, g.foracid AS accountnum, g.cif_id AS cifid
            FROM tbaadm.ctd_dtd_acli_view h
            JOIN tbaadm.gam g ON g.acid = h.acid AND g.bank_id = h.bank_id AND g.del_flg = 'N'
            WHERE h.bank_id = '01' AND h.pstd_flg = 'Y' AND ROWNUM <= ?
            """);
        SOURCE.put("Transaction", "tbaadm.ctd_dtd_acli_view (= htd UNION dtd) + tbaadm.gam");
    }

    public static final String BRANCH_FALLBACK =
            "SELECT DISTINCT sol_id AS solid FROM tbaadm.gam WHERE ROWNUM <= ?";
    public static final String SCHEME_CODE_FALLBACK =
            "SELECT DISTINCT schm_code AS code FROM tbaadm.gam WHERE ROWNUM <= ?";
    public static final String SCHEME_TYPE_FALLBACK =
            "SELECT DISTINCT schm_type AS type FROM tbaadm.gam WHERE ROWNUM <= ?";

    public static boolean known(String entity) { return SQL.containsKey(entity); }
    public static Set<String> entities() { return SQL.keySet(); }
}
