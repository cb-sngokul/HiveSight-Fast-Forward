const API_BASE = '/api';

function showLoading(show) {
    document.getElementById('loading').classList.toggle('d-none', !show);
}

function showError(msg) {
    const el = document.getElementById('error');
    el.textContent = msg;
    el.classList.remove('d-none');
}

function hideError() {
    document.getElementById('error').classList.add('d-none');
}

function renderTimeline(events) {
    const container = document.getElementById('timeline');
    container.innerHTML = '';

    const icons = {
        renewal: '📆',
        ramp_applied: '🔄',
        cancelled: '❌'
    };

    events.forEach(e => {
        const div = document.createElement('div');
        div.className = 'timeline-item';
        div.innerHTML = `
            <div class="timeline-icon ${e.type}">${icons[e.type] || '•'}</div>
            <div class="timeline-content">
                <div class="timeline-date">${e.dateFormatted}</div>
                <div class="timeline-desc">${e.description}</div>
            </div>
        `;
        container.appendChild(div);
    });
}

function formatDate(ts) {
    return ts ? new Date(ts * 1000).toISOString().split('T')[0] : '—';
}

function renderDetailedReport(data) {
    const report = document.getElementById('detailedReport');
    report.classList.remove('d-none');

    const events = data.events || [];
    const renewals = events.filter(e => e.type === 'renewal').length;
    const ramps = events.filter(e => e.type === 'ramp_applied').length;
    const cancelled = events.some(e => e.type === 'cancelled');

    document.getElementById('statRenewals').textContent = renewals;
    document.getElementById('statRamps').textContent = ramps;
    document.getElementById('statWindow').textContent = data.simulationStart && data.simulationEnd
        ? `${formatDate(data.simulationStart)} → ${formatDate(data.simulationEnd)}`
        : '18 months';

    document.getElementById('chargebeeNextBilling').innerHTML = data.chargebeeUiNextBilling
        ? `<strong>${formatDate(data.chargebeeUiNextBilling)}</strong><br><small class="text-muted">Next invoice date (Chargebee)</small>`
        : '—';
    document.getElementById('hivesightNextBilling').innerHTML = data.hivesightNextBilling
        ? `<strong>${formatDate(data.hivesightNextBilling)}</strong><br><small class="text-muted">Last renewal in 18‑month window</small>`
        : '—';

    const insightEl = document.getElementById('reportInsight');
    if (ramps > 0) {
        insightEl.classList.remove('d-none');
        insightEl.innerHTML = `<strong>Ramp detected:</strong> ${ramps} scheduled change(s) will be applied during the simulation. Chargebee's "next billing" may not reflect these changes—HiveSight shows the corrected timeline.`;
    } else if (cancelled) {
        insightEl.classList.remove('d-none');
        const lastEvent = events.find(e => e.type === 'cancelled');
        insightEl.innerHTML = `<strong>Subscription ends:</strong> ${lastEvent ? lastEvent.dateFormatted : '—'}. Use "Validate Ghost of March" to verify the expected cancellation date.`;
    } else {
        insightEl.classList.add('d-none');
    }
}

function renderValidationBadge(passed, message, data) {
    const badge = document.getElementById('validationBadge');
    const resultCard = document.getElementById('validationResult');

    if (passed !== null && passed !== undefined) {
        badge.classList.remove('d-none');
        badge.className = 'badge ' + (passed ? 'bg-success' : 'bg-danger');
        badge.textContent = passed ? '✓ PASS' : '✗ FAIL';
        badge.title = message || '';

        // Verbose validation result card - shown at TOP of results
        resultCard.classList.remove('d-none');
        const expected = document.getElementById('expectedCancel')?.value || '—';
        const events = data?.events || [];
        const lastEvent = events[events.length - 1];

        if (passed) {
            resultCard.innerHTML = `
                <h6 class="text-uppercase text-muted mb-2">Ghost of March Validation</h6>
                <div class="alert alert-success mb-0 py-3">
                    <h5 class="alert-heading mb-2">✓ Validation Passed</h5>
                    <p class="mb-1 fs-6"><strong>Result:</strong> The subscription will correctly terminate on <strong>${lastEvent?.dateFormatted || expected}</strong>.</p>
                    <p class="mb-0">The contract ends as legally required.</p>
                </div>
            `;
        } else {
            const actualDesc = lastEvent
                ? `${lastEvent.type} on ${lastEvent.dateFormatted}`
                : 'no events in simulation';
            const expectedLine = expected ? `<p class="mb-1"><strong>Expected:</strong> Subscription to cancel on <strong>${expected}</strong></p>` : '';
            resultCard.innerHTML = `
                <h6 class="text-uppercase text-muted mb-2">Ghost of March Validation</h6>
                <div class="alert alert-danger mb-0 py-3">
                    <h5 class="alert-heading mb-2">✗ Validation Failed</h5>
                    ${expectedLine}
                    <p class="mb-1"><strong>Actual:</strong> ${actualDesc}</p>
                    <p class="mb-0">Verify the cancellation is scheduled correctly in Chargebee, or ensure the expected date falls within the 18-month simulation window.</p>
                </div>
            `;
        }
    } else {
        badge.classList.add('d-none');
        resultCard.classList.add('d-none');
        resultCard.innerHTML = '';
    }
}

document.getElementById('btnFetchSubs').addEventListener('click', async () => {
    const listEl = document.getElementById('subscriptionList');
    listEl.innerHTML = '<small class="text-muted">Loading...</small>';
    try {
        const res = await fetch(`${API_BASE}/subscriptions?has_scheduled_changes=true`);
        const data = await res.json();
        if (data.subscriptions && data.subscriptions.length > 0) {
            listEl.innerHTML = '<small class="text-muted mb-2">Click to select:</small>' +
                data.subscriptions.map(s => `
                    <div class="subscription-option" data-id="${s.id}">
                        <strong>${s.id}</strong> — ${s.status} (next: ${s.next_billing_at ? new Date(s.next_billing_at * 1000).toLocaleDateString() : 'N/A'})
                    </div>
                `).join('');
            listEl.querySelectorAll('.subscription-option').forEach(el => {
                el.addEventListener('click', () => {
                    listEl.querySelectorAll('.subscription-option').forEach(x => x.classList.remove('selected'));
                    el.classList.add('selected');
                    document.getElementById('subscriptionId').value = el.dataset.id;
                });
            });
        } else {
            const resAll = await fetch(`${API_BASE}/subscriptions`);
            const dataAll = await resAll.json();
            if (dataAll.subscriptions && dataAll.subscriptions.length > 0) {
                listEl.innerHTML = '<small class="text-muted mb-2">No subscriptions with ramps. All subscriptions (click to select):</small>' +
                    dataAll.subscriptions.map(s => `
                        <div class="subscription-option" data-id="${s.id}">
                            <strong>${s.id}</strong> — ${s.status}
                        </div>
                    `).join('');
                listEl.querySelectorAll('.subscription-option').forEach(el => {
                    el.addEventListener('click', () => {
                        listEl.querySelectorAll('.subscription-option').forEach(x => x.classList.remove('selected'));
                        el.classList.add('selected');
                        document.getElementById('subscriptionId').value = el.dataset.id;
                    });
                });
            } else {
                listEl.innerHTML = '<small class="text-warning">No subscriptions found. Check Chargebee config.</small>';
            }
        }
    } catch (e) {
        listEl.innerHTML = `<small class="text-danger">Error: ${e.message}</small>`;
    }
});

document.getElementById('btnSimulate').addEventListener('click', async () => {
    const id = document.getElementById('subscriptionId').value.trim();
    if (!id) {
        showError('Please enter a subscription ID');
        return;
    }
    hideError();
    showLoading(true);
    try {
        const res = await fetch(`${API_BASE}/simulate/${encodeURIComponent(id)}?months=18`);
        const data = await res.json();
        if (!res.ok) throw new Error(data.message || 'Simulation failed');
        renderTimeline(data.events || []);
        renderDetailedReport(data);
        renderValidationBadge(null, null, null);
    } catch (e) {
        showError(e.message);
    } finally {
        showLoading(false);
    }
});

document.getElementById('btnValidate').addEventListener('click', async () => {
    const id = document.getElementById('subscriptionId').value.trim();
    const expected = document.getElementById('expectedCancel').value;
    if (!id) {
        showError('Please enter a subscription ID');
        return;
    }
    hideError();
    showLoading(true);
    try {
        let url = `${API_BASE}/validate/ghost-of-march/${encodeURIComponent(id)}`;
        if (expected) url += `?expected_cancel=${encodeURIComponent(expected)}`;
        const res = await fetch(url);
        const data = await res.json();
        if (!res.ok) throw new Error(data.message || 'Validation failed');
        renderTimeline(data.events || []);
        renderDetailedReport(data);
        renderValidationBadge(data.validationPassed, data.validationMessage, data);
    } catch (e) {
        showError(e.message);
    } finally {
        showLoading(false);
    }
});
