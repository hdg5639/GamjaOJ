"""Common, owner-scoped cleanup for synthetic generation probes. Call inside their transaction."""
def cleanup_sql(owners):
    checks="SELECT id FROM generation_resource_check WHERE problem_version IN (SELECT id FROM problem_version WHERE owner_id IN ("+owners+"))"
    jobs="SELECT id FROM generation_job WHERE owner_id IN ("+owners+")"
    drafts="SELECT id FROM generation_spec_draft WHERE owner_id IN ("+owners+")"
    rules="SELECT id FROM hybrid_generation WHERE owner_id IN ("+owners+")"
    ids=checks+" UNION "+jobs+" UNION "+drafts+" UNION "+rules
    return ("DELETE FROM generation_recovery_receipt WHERE job_id IN ("+ids+"); "
            "DELETE FROM generation_recovery_attempt WHERE job_id IN ("+jobs+" UNION "+drafts+" UNION "+rules+"); "
            "DELETE FROM generation_resource_attempt WHERE check_id IN ("+checks+"); "
            "DELETE FROM generation_resource_execution WHERE check_id IN ("+checks+"); "
            "DELETE FROM generation_resource_check WHERE id IN ("+checks+"); "
            "DELETE FROM generation_prose_review WHERE job_id IN ("+jobs+"); ")


def active_checks_sql(owners):
    return ("SELECT count(*) FROM generation_resource_check c JOIN problem_version p ON p.id=c.problem_version "
            "WHERE p.owner_id IN ("+owners+") AND c.status IN ('QUEUED','GENERATING','MEASURING','REPLAYING')")
