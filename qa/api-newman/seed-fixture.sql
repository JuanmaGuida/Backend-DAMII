INSERT INTO categories (name, description, active, created_at, updated_at)
VALUES ('DDA2 QA isolated category', 'Synthetic QA fixture', TRUE, now(), now())
ON CONFLICT (name) DO UPDATE SET description = EXCLUDED.description, active = TRUE, updated_at = now();

INSERT INTO subcategories (category_id, name, description, active)
SELECT id, 'DDA2 QA isolated subcategory', 'Synthetic QA fixture', TRUE
FROM categories WHERE name = 'DDA2 QA isolated category'
ON CONFLICT (category_id, name) DO UPDATE SET description = EXCLUDED.description, active = TRUE;

INSERT INTO request_types (
  code, subcategory_id, name, description, ticket_type, responsible_area_id,
  minimum_priority, base_risk, affected_population_factor, allows_anonymous,
  requires_location, active
)
SELECT 'DDA2_QA_MASS_ASSIGNMENT', s.id, 'DDA2 QA mass assignment', 'Synthetic fixture',
       'COMPLAINT', 'M6', 'LOW', 'LOW', 0.0, FALSE, FALSE, TRUE
FROM subcategories s JOIN categories c ON c.id = s.category_id
WHERE c.name = 'DDA2 QA isolated category' AND s.name = 'DDA2 QA isolated subcategory'
ON CONFLICT (code) DO UPDATE SET
  subcategory_id = EXCLUDED.subcategory_id,
  name = EXCLUDED.name,
  description = EXCLUDED.description,
  responsible_area_id = EXCLUDED.responsible_area_id,
  minimum_priority = EXCLUDED.minimum_priority,
  base_risk = EXCLUDED.base_risk,
  affected_population_factor = EXCLUDED.affected_population_factor,
  allows_anonymous = EXCLUDED.allows_anonymous,
  requires_location = EXCLUDED.requires_location,
  active = TRUE;

INSERT INTO request_types (
  code, subcategory_id, name, description, ticket_type, responsible_area_id,
  minimum_priority, base_risk, affected_population_factor, allows_anonymous,
  requires_location, active
)
SELECT 'DDA2_QA_BFLA_M9', s.id, 'DDA2 QA out-of-area fixture', 'Synthetic fixture',
       'COMPLAINT', 'M9', 'LOW', 'LOW', 0.0, FALSE, FALSE, TRUE
FROM subcategories s JOIN categories c ON c.id = s.category_id
WHERE c.name = 'DDA2 QA isolated category' AND s.name = 'DDA2 QA isolated subcategory'
ON CONFLICT (code) DO UPDATE SET
  subcategory_id = EXCLUDED.subcategory_id,
  name = EXCLUDED.name,
  description = EXCLUDED.description,
  responsible_area_id = EXCLUDED.responsible_area_id,
  minimum_priority = EXCLUDED.minimum_priority,
  base_risk = EXCLUDED.base_risk,
  affected_population_factor = EXCLUDED.affected_population_factor,
  allows_anonymous = EXCLUDED.allows_anonymous,
  requires_location = EXCLUDED.requires_location,
  active = TRUE;
