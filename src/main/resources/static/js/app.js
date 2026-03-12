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

function renderTimeline(events, timezone) {
    const container = document.getElementById('timeline');
    container.innerHTML = '';

    const icons = {
        renewal: '📆',
        ramp_applied: '🔄',
        cancelled: '❌',
        trial_end: '🧪',
        paused: '⏸️',
        resumed: '▶️',
        non_renewing: '🔚',
        contract_end: '📋'
    };

    events.forEach(e => {
        const div = document.createElement('div');
        div.className = 'timeline-item';
        const amountHtml = (e.amount != null && e.amount !== undefined)
            ? `<div class="timeline-amount text-success fw-semibold">${formatAmount(e.amount, e.currencyCode)}</div>`
            : '';
        const dateDisplay = e.date ? formatDateTime(e.date, timezone) : (e.dateFormatted || '—');
        div.innerHTML = `
            <div class="timeline-icon ${e.type}">${icons[e.type] || '•'}</div>
            <div class="timeline-content">
                <div class="d-flex justify-content-between align-items-start flex-wrap gap-1">
                    <div>
                        <div class="timeline-date" title="${dateDisplay}">${dateDisplay}</div>
                        <div class="timeline-desc">${e.description}</div>
                    </div>
                    ${amountHtml}
                </div>
            </div>
        `;
        container.appendChild(div);
    });
}

function formatDate(ts) {
    return ts ? new Date(ts * 1000).toISOString().split('T')[0] : '—';
}

/** Full timestamp in site timezone (e.g. 2026-09-12 00:00:00 IST). */
function formatDateTime(ts, timezone) {
    if (!ts) return '—';
    const d = new Date(ts * 1000);
    const tz = timezone || 'UTC';
    const dateOpts = { timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false };
    const dateStr = d.toLocaleString('en-CA', dateOpts).replace(',', '');
    const tzParts = new Intl.DateTimeFormat('en', { timeZone: tz, timeZoneName: 'short' }).formatToParts(d);
    const tzAbbr = tzParts.find(p => p.type === 'timeZoneName')?.value || tz;
    return dateStr + ' ' + tzAbbr;
}

/** Format amount in minor units (cents) to display string. Handles zero-decimal currencies (JPY, etc.). */
function formatAmount(amount, currencyCode) {
    if (amount == null || amount === undefined) return '';
    const currency = (currencyCode || 'USD').toUpperCase();
    const zeroDecimal = ['JPY', 'KRW', 'VND', 'CLP', 'XOF', 'XAF'].includes(currency);
    const value = zeroDecimal ? amount : (amount / 100);
    return new Intl.NumberFormat('en-US', {
        style: 'currency',
        currency: currency,
        minimumFractionDigits: zeroDecimal ? 0 : 2,
        maximumFractionDigits: zeroDecimal ? 0 : 2
    }).format(value);
}

let invoiceChartInstance = null;

function getMonthKeyInTz(epochSec, tz) {
    const d = new Date(epochSec * 1000);
    const s = d.toLocaleDateString('en-CA', { timeZone: tz });
    return s.substring(0, 7); // "YYYY-MM"
}

const MONTH_NAMES = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
const MONTH_NAMES_FULL = ['January','February','March','April','May','June','July','August','September','October','November','December'];
function getMonthLabel(year, month) {
    return `${MONTH_NAMES[month - 1]} ${year}`;
}
function getMonthLabelFull(year, month) {
    return `${MONTH_NAMES_FULL[month - 1]} ${year}`;
}

function buildInvoiceTrendData(data) {
    const events = data.events || [];
    const tz = data.timezone || 'UTC';
    const currencyCode = data.currencyCode || 'USD';

    // Get events with amounts (renewals, ramp_applied)
    const eventsWithAmount = events.filter(e => e.amount != null && e.amount !== undefined);
    if (eventsWithAmount.length === 0) return null;

    const startTs = data.simulationStart;
    const endTs = data.simulationEnd;
    if (!startTs || !endTs) return null;

    // Build month buckets from simulation window (in timezone tz)
    const monthMap = new Map();
    const startKey = getMonthKeyInTz(startTs, tz);
    const endKey = getMonthKeyInTz(endTs, tz);
    const [sy, sm] = startKey.split('-').map(Number);
    const [ey, em] = endKey.split('-').map(Number);
    let y = sy, m = sm;
    while (y < ey || (y === ey && m <= em)) {
        const key = `${y}-${String(m).padStart(2, '0')}`;
        monthMap.set(key, { label: getMonthLabel(y, m), amountCents: 0 });
        m++;
        if (m > 12) { m = 1; y++; }
    }

    // Sum amounts by month (event date in site timezone)
    eventsWithAmount.forEach(e => {
        const monthKey = getMonthKeyInTz(e.date, tz);
        if (monthMap.has(monthKey)) {
            monthMap.get(monthKey).amountCents += e.amount;
        }
    });

    const labels = [];
    const values = [];
    const sortedKeys = [...monthMap.keys()].sort();
    sortedKeys.forEach(k => {
        const b = monthMap.get(k);
        labels.push(b.label);
        values.push(b.amountCents);
    });
    return { labels, values, currencyCode };
}

function renderInvoiceTrendChart(data) {
    const container = document.getElementById('invoiceTrendChart');
    const canvas = document.getElementById('invoiceChart');
    if (!container || !canvas) return;

    const chartData = buildInvoiceTrendData(data);
    if (!chartData || chartData.labels.length === 0) {
        container.classList.add('d-none');
        return;
    }

    container.classList.remove('d-none');

    if (invoiceChartInstance) {
        invoiceChartInstance.destroy();
        invoiceChartInstance = null;
    }

    const ctx = canvas.getContext('2d');
    invoiceChartInstance = new Chart(ctx, {
        type: 'line',
        data: {
            labels: chartData.labels,
            datasets: [{
                label: 'Invoice Amount',
                data: chartData.values,
                borderColor: 'rgb(13, 110, 253)',
                backgroundColor: 'rgba(13, 110, 253, 0.1)',
                fill: true,
                tension: 0.2
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: { display: false },
                tooltip: {
                    callbacks: {
                        label: (ctx) => formatAmount(ctx.raw, chartData.currencyCode)
                    }
                }
            },
            scales: {
                y: {
                    beginAtZero: true,
                    ticks: {
                        callback: (v) => formatAmount(v, chartData.currencyCode)
                    }
                }
            }
        }
    });
}

function getCancelledMonthLabel(events, tz) {
    const cancelled = events?.find(e => e.type === 'cancelled');
    if (!cancelled || !cancelled.date) return null;
    const d = new Date(cancelled.date * 1000);
    const month = d.toLocaleDateString('en-US', { timeZone: tz, month: 'long', year: 'numeric' });
    return month;
}

function renderMonthlyBreakdown(data) {
    const container = document.getElementById('monthlyBreakdown');
    const listEl = document.getElementById('monthlyBreakdownList');
    if (!container || !listEl) return;

    const breakdowns = data.monthlyBreakdowns || [];
    const tz = data.timezone || 'UTC';
    const currencyCode = data.currencyCode || 'USD';
    const cancelledMonth = getCancelledMonthLabel(data.events, tz);

    if (breakdowns.length === 0 && !cancelledMonth) {
        container.classList.add('d-none');
        return;
    }

    container.classList.remove('d-none');

    const breakdownCards = breakdowns.map(b => {
        const discountLine = b.discountCents > 0
            ? `<div class="d-flex justify-content-between"><span>Manual Discount:</span><span class="text-danger">-${formatAmount(b.discountCents, currencyCode)}</span></div>`
            : '';
        const taxLabel = b.taxRatePercent != null ? `Tax (${b.taxRatePercent}%):` : 'Tax:';
        const taxLine = (b.taxRatePercent != null || b.taxCents > 0)
            ? `<div class="d-flex justify-content-between"><span>${taxLabel}</span><span>${formatAmount(b.taxCents, currencyCode)}</span></div>`
            : '';
        const impactLine = b.impactVsPreviousCents != null
            ? `<div class="mt-2 small ${b.impactVsPreviousCents >= 0 ? 'text-success' : 'text-danger'}"><strong>Impact:</strong> ${formatAmount(Math.abs(b.impactVsPreviousCents), currencyCode)} ${b.impactVsPreviousCents >= 0 ? 'increase' : 'decrease'} vs previous month</div>`
            : '';

        return `
            <div class="card mb-3 monthly-breakdown-card">
                <div class="card-header py-2 bg-light">
                    <strong>${b.monthLabel}</strong>
                </div>
                <div class="card-body py-3 small">
                    <div class="mb-2">
                        <div class="d-flex justify-content-between"><span>Unit Price:</span><span>${formatAmount(b.unitPrice, currencyCode)}</span></div>
                        <div class="d-flex justify-content-between"><span>Quantity:</span><span>${b.quantity}</span></div>
                        <div class="d-flex justify-content-between"><span>Subtotal:</span><span>${formatAmount(b.subtotalCents, currencyCode)}</span></div>
                        ${discountLine}
                        ${taxLine}
                        <div class="d-flex justify-content-between mt-2 pt-2 border-top"><span><strong>Total:</strong></span><span><strong>${formatAmount(b.totalCents, currencyCode)}</strong></span></div>
                    </div>
                    <div class="mt-2">
                        <strong>Change:</strong>
                        <ul class="mb-0 ps-3 mt-1">${(b.changes || []).map(c => `<li>${c}</li>`).join('')}</ul>
                    </div>
                    ${impactLine}
                </div>
            </div>
        `;
    }).join('');

    const cancellationCard = cancelledMonth
        ? `<div class="card mb-3 monthly-breakdown-card border-danger">
            <div class="card-header py-2 bg-danger text-white">
                <strong>Subscription cancelled</strong>
            </div>
            <div class="card-body py-3 small">
                Subscription cancelled in <strong>${cancelledMonth}</strong>.
            </div>
        </div>`
        : '';

    listEl.innerHTML = breakdownCards + cancellationCard;
}

function renderDetailedReport(data) {
    const report = document.getElementById('detailedReport');
    report.classList.remove('d-none');

    const events = data.events || [];
    const renewals = events.filter(e => e.type === 'renewal').length;
    const ramps = events.filter(e => e.type === 'ramp_applied').length;
    const cancelled = events.some(e => e.type === 'cancelled');
    const totalRevenue = events
        .filter(e => e.amount != null && e.amount !== undefined)
        .reduce((sum, e) => sum + e.amount, 0);

    document.getElementById('statRenewals').textContent = renewals;
    document.getElementById('statRamps').textContent = ramps;
    document.getElementById('statTotalRevenue').textContent = totalRevenue > 0
        ? formatAmount(totalRevenue, data.currencyCode)
        : '—';
    document.getElementById('statWindow').textContent = data.simulationStart && data.simulationEnd
        ? `${formatDate(data.simulationStart)} → ${formatDate(data.simulationEnd)}`
        : '18 months';

    const cancelledMonthLabel = getCancelledMonthLabel(events, data.timezone);
    const cancelledRow = document.getElementById('statCancelledRow');
    const statCancelled = document.getElementById('statCancelled');
    if (cancelledRow && statCancelled) {
        if (cancelledMonthLabel) {
            cancelledRow.classList.remove('d-none');
            statCancelled.textContent = cancelledMonthLabel;
        } else {
            cancelledRow.classList.add('d-none');
        }
    }

    document.getElementById('chargebeeNextBilling').innerHTML = data.chargebeeUiNextBilling
        ? `<strong>${formatDate(data.chargebeeUiNextBilling)}</strong><br><small class="text-muted">Next invoice date (Chargebee)</small>`
        : '—';
    document.getElementById('hivesightNextBilling').innerHTML = data.hivesightNextBilling
        ? `<strong>${formatDate(data.hivesightNextBilling)}</strong><br><small class="text-muted">Last renewal in simulation window</small>`
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

// Set default start/end months: current month to 18 months from now
document.addEventListener('DOMContentLoaded', () => {
    const now = new Date();
    const startEl = document.getElementById('startMonth');
    const endEl = document.getElementById('endMonth');
    if (startEl && !startEl.value) {
        startEl.value = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
    }
    if (endEl && !endEl.value) {
        const endDate = new Date(now);
        endDate.setMonth(endDate.getMonth() + 18);
        endEl.value = `${endDate.getFullYear()}-${String(endDate.getMonth() + 1).padStart(2, '0')}`;
    }
});

function formatDateLong(ts, timezone) {
    if (!ts) return '—';
    const d = new Date(ts * 1000);
    const tz = timezone || 'UTC';
    return d.toLocaleDateString('en-US', { timeZone: tz, year: 'numeric', month: 'short', day: 'numeric' });
}

function renderSubscriptionDetailsCard(data) {
    const cardEl = document.getElementById('subscriptionDetailsCard');
    if (!data || !data.id) {
        cardEl.classList.add('d-none');
        cardEl.innerHTML = '';
        return;
    }
    const contractStart = data.contractStart ? formatDateLong(data.contractStart, data.timezone) : '—';
    const contractEnd = data.contractEnd ? formatDateLong(data.contractEnd, data.timezone) : '—';
    const contractLine = (contractStart !== '—' || contractEnd !== '—')
        ? `Contract Term: ${contractStart} → ${contractEnd}`
        : null;
    const billing = data.billingDisplay || '—';
    const taxRate = data.taxRate != null ? `${data.taxRate}%` : '—';
    const changes = data.upcomingChanges || [];
    const changesHtml = changes.length > 0
        ? `<div class="mt-2"><strong>Upcoming Changes:</strong><ul class="mb-0 ps-3 mt-1">${changes.map(c => `<li>${c}</li>`).join('')}</ul></div>`
        : '';

    cardEl.innerHTML = `
        <div class="card border-primary mt-3">
            <div class="card-header bg-primary text-white py-2">
                <h6 class="mb-0">${data.id}</h6>
            </div>
            <div class="card-body py-3">
                ${contractLine ? `<p class="mb-1 small"><strong>Contract Term:</strong> ${contractStart} → ${contractEnd}</p>` : ''}
                <p class="mb-1 small"><strong>Billing:</strong> ${billing}</p>
                <p class="mb-1 small"><strong>Tax Rate:</strong> ${taxRate}</p>
                ${changesHtml}
            </div>
        </div>
    `;
    cardEl.classList.remove('d-none');
}

async function loadSubscriptionDetails() {
    const id = document.getElementById('subscriptionId')?.value?.trim();
    if (!id) {
        showError('Please enter a subscription ID');
        return;
    }
    hideError();
    const cardEl = document.getElementById('subscriptionDetailsCard');
    cardEl.innerHTML = '<div class="text-center py-3 text-muted"><small>Loading...</small></div>';
    cardEl.classList.remove('d-none');
    try {
        const res = await fetch(`${API_BASE}/subscription/${encodeURIComponent(id)}/details`);
        const data = await res.json();
        if (!res.ok) throw new Error(data.message || 'Failed to load details');
        renderSubscriptionDetailsCard(data);
    } catch (e) {
        cardEl.innerHTML = `<div class="alert alert-danger py-2 mb-0 small">${e.message}</div>`;
    }
}

function getSimulationParams() {
    const start = document.getElementById('startMonth')?.value;
    const end = document.getElementById('endMonth')?.value;
    const params = new URLSearchParams();
    if (start) params.set('start_month', start);
    if (end) params.set('end_month', end);
    return params.toString();
}

document.getElementById('btnLoadDetails').addEventListener('click', () => loadSubscriptionDetails());

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
                    loadSubscriptionDetails();
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
                        loadSubscriptionDetails();
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
        const qs = getSimulationParams();
        const res = await fetch(`${API_BASE}/simulate/${encodeURIComponent(id)}${qs ? '?' + qs : ''}`);
        const data = await res.json();
        if (!res.ok) throw new Error(data.message || 'Simulation failed');
        renderTimeline(data.events || [], data.timezone);
        renderDetailedReport(data);
        renderInvoiceTrendChart(data);
        renderMonthlyBreakdown(data);
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
        const params = new URLSearchParams(getSimulationParams());
        if (expected) params.set('expected_cancel', expected);
        const qs = params.toString();
        const url = `${API_BASE}/validate/ghost-of-march/${encodeURIComponent(id)}${qs ? '?' + qs : ''}`;
        const res = await fetch(url);
        const data = await res.json();
        if (!res.ok) throw new Error(data.message || 'Validation failed');
        renderTimeline(data.events || [], data.timezone);
        renderDetailedReport(data);
        renderInvoiceTrendChart(data);
        renderMonthlyBreakdown(data);
        renderValidationBadge(data.validationPassed, data.validationMessage, data);
    } catch (e) {
        showError(e.message);
    } finally {
        showLoading(false);
    }
});
