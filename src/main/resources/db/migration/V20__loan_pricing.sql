-- Approved price for the INSTANT_10K product: 1% flat over its 30-day term, so
-- a KES 10,000 loan costs KES 100 in interest and KES 10,100 is repaid.
--
-- No processing fee. Pricing is time-versioned: to change it, end this range
-- and insert the next one in a new migration; never edit this row.
INSERT INTO loan.loan_product_pricing (product_id, valid_during, interest_rate, processing_fee, approved_by)
SELECT p.id, daterange(DATE '2026-09-25', NULL), 0.0100, 0.00, 'V20__loan_pricing'
FROM loan.loan_product p
WHERE p.code = 'INSTANT_10K';
