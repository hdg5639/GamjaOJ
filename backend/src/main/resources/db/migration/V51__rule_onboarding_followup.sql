-- Onboarding can continue into one problem generation from the new rule; the request JSON carries difficulty/style/target.
ALTER TABLE hybrid_rule_onboarding ADD COLUMN followup_generation_id UUID;
ALTER TABLE hybrid_rule_onboarding ADD COLUMN followup_error VARCHAR(160);
