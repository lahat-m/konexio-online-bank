SELECT set_config('konexio.actor_type',    'SYSTEM',          true);
SELECT set_config('konexio.change_reason', 'SEED_GL_ACCOUNT', true);

-- Internal GL accounts: 12-digit numbers starting 9000, valid Luhn check digit.
INSERT INTO account.account (account_number, account_class, account_type, normal_balance, currency, nickname)
SELECT v.payload || common.luhn_check_digit(v.payload)::text,
       'INTERNAL', v.account_type, v.normal_balance, 'KES', v.nickname
FROM (VALUES
        ('90000000001', 'MPESA_CLEARING',  'DEBIT',  'M-Pesa clearing'),
        ('90000000002', 'CARD_CLEARING',   'DEBIT',  'Card clearing'),
        ('90000000003', 'AGENT_CLEARING',  'DEBIT',  'Agent float clearing'),
        ('90000000004', 'FEE_INCOME',      'CREDIT', 'Fee income'),
        ('90000000005', 'INTEREST_INCOME', 'CREDIT', 'Interest income')
     ) AS v (payload, account_type, normal_balance, nickname);

-- Bonus feature product. term_days = 30 is a PLACEHOLDER: confirm with credit policy.
INSERT INTO loan.loan_product (code, name, principal, currency, term_days)
VALUES ('INSTANT_10K', 'Instant loan KES 10,000', 10000.00, 'KES', 30);
