# Shared by older HTTP regressions: let the real consumer own notification writes.
function Wait-OrderMatching([long[]]$OrderIds) {
    if ($OrderIds.Count -eq 0) { return }
    $settle = @'
import sys
sys.path.insert(0, sys.argv[1])
import day19_support as s
s.load_env()
ids = ','.join(str(int(value)) for value in sys.argv[2:])
def settled():
    missing = s.number("SELECT COUNT(*) FROM order_db.outbox_event e LEFT JOIN "
                       "driver_db.processed_order_event p ON p.order_id=e.aggregate_id AND p.event_id=e.event_id "
                       f"WHERE e.aggregate_id IN ({ids}) AND (e.status<>'SENT' OR p.order_id IS NULL)")
    q = s.queue()
    return missing == 0 and q.get('messages_ready',0) == 0 and q.get('messages_unacknowledged',0) == 0
s.wait_until(settled, 'Test outbox/MQ has not settled; preserve test rows for recovery')
'@
    & python -c $settle $PSScriptRoot @OrderIds
    if ($LASTEXITCODE -ne 0) { throw 'HTTP fixture matching/ACK did not settle; no SQL rows were deleted' }
}
