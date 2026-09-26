-- NULL preserves legacy whole-bank start requests; explicit scopes are canonical sorted JSON.
ALTER TABLE diagnostic_session ADD COLUMN requested_categories_json TEXT;
