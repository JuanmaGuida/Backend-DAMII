INSERT INTO categories (name, description, active, created_at, updated_at)
SELECT 'DDA2 QA isolated category', 'Synthetic QA fixture', TRUE, now(), now()
WHERE NOT EXISTS (
  SELECT 1 FROM categories WHERE lower(name) = lower('DDA2 QA isolated category')
);

INSERT INTO subcategories (category_id, name, description, active)
SELECT c.id, 'DDA2 QA isolated subcategory', 'Synthetic QA fixture', TRUE
FROM categories c
WHERE lower(c.name) = lower('DDA2 QA isolated category')
  AND NOT EXISTS (
    SELECT 1 FROM subcategories s
    WHERE s.category_id = c.id
      AND lower(s.name) = lower('DDA2 QA isolated subcategory')
  );

INSERT INTO request_types (
  code, subcategory_id, name, description, ticket_type, responsible_area_id,
  minimum_priority, base_risk, affected_population_factor, allows_anonymous,
  requires_location, active
)
SELECT 'DDA2_QA_MASS_ASSIGNMENT', s.id, 'DDA2 QA mass assignment', 'Synthetic fixture',
       'COMPLAINT', 'M6', 'LOW', 'LOW', 0.0, FALSE, FALSE, TRUE
FROM subcategories s JOIN categories c ON c.id = s.category_id
WHERE lower(c.name) = lower('DDA2 QA isolated category')
  AND lower(s.name) = lower('DDA2 QA isolated subcategory')
  AND NOT EXISTS (
    SELECT 1 FROM request_types rt WHERE lower(rt.code) = lower('DDA2_QA_MASS_ASSIGNMENT')
  );

INSERT INTO request_types (
  code, subcategory_id, name, description, ticket_type, responsible_area_id,
  minimum_priority, base_risk, affected_population_factor, allows_anonymous,
  requires_location, active
)
SELECT 'DDA2_QA_BFLA_M9', s.id, 'DDA2 QA out-of-area fixture', 'Synthetic fixture',
       'COMPLAINT', 'M9', 'LOW', 'LOW', 0.0, FALSE, FALSE, TRUE
FROM subcategories s JOIN categories c ON c.id = s.category_id
WHERE lower(c.name) = lower('DDA2 QA isolated category')
  AND lower(s.name) = lower('DDA2 QA isolated subcategory')
  AND NOT EXISTS (
    SELECT 1 FROM request_types rt WHERE lower(rt.code) = lower('DDA2_QA_BFLA_M9')
  );
