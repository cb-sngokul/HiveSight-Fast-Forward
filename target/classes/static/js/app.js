const API_BASE = '/api';

/** Last simulation/validation result, used for Export CSV (single subscription). */
let lastSimulationResult = null;

/** Results from Simulate Batch: array of simulation result objects. When set, Export uses this for all handles. */
let lastBatchResults = null;

/** Escape a value for CSV (wrap in quotes if contains comma, quote, or newline). */
function csvEscape(val) {
    if (val == null || val === undefined) return '';
    const s = String(val);
    if (/[,"\r\n]/.test(s)) return '"' + s.replace(/"/g, '""') + '"';
    return s;
}

/** Build CSV string from current simulation result (what's shown in the UI). */
function buildExportCsv(data) {
    const events = data.events || [];
    const renewals = events.filter(e => e.type === 'renewal').length;
    const ramps = events.filter(e => e.type === 'ramp_applied').length;
    const totalRevenue = events
        .filter(e => e.amount != null && e.amount !== undefined)
        .reduce((sum, e) => sum + e.amount, 0);
    const revDisplay = totalRevenue > 0 ? formatAmount(totalRevenue, data.currencyCode) : '—';
    const windowStr = data.simulationStart && data.simulationEnd
        ? `${formatDate(data.simulationStart)} → ${formatDate(data.simulationEnd)}`
        : '18 months';

    const rows = [];
    // Summary header + row
    rows.push(['subscription_id', 'customer_id', 'simulation_start', 'simulation_end', 'renewals_count', 'ramps_applied', 'simulation_window', 'total_revenue', 'currency_code', 'chargebee_next_billing', 'hivesight_next_billing', 'validation_passed', 'validation_message', 'timezone'].join(','));
    rows.push([
        data.subscriptionId ?? '',
        data.customerId ?? '',
        formatDate(data.simulationStart) ?? '',
        formatDate(data.simulationEnd) ?? '',
        renewals,
        ramps,
        windowStr,
        revDisplay,
        data.currencyCode ?? '',
        formatDate(data.chargebeeUiNextBilling) ?? '',
        formatDate(data.hivesightNextBilling) ?? '',
        data.validationPassed != null ? data.validationPassed : '',
        data.validationMessage ?? '',
        data.timezone ?? ''
    ].map(csvEscape).join(','));

    rows.push('');
    rows.push(['event_date', 'event_type', 'description', 'amount_display', 'amount_minor', 'currency_code'].join(','));
    events.forEach(e => {
        const amountDisplay = (e.amount != null && e.amount !== undefined) ? formatAmount(e.amount, e.currencyCode) : '';
        rows.push([
            e.dateFormatted ?? formatDate(e.date) ?? '',
            e.type ?? '',
            e.description ?? '',
            amountDisplay,
            e.amount != null && e.amount !== undefined ? e.amount : '',
            e.currencyCode ?? ''
        ].map(csvEscape).join(','));
    });
    return rows.join('\r\n');
}

/** Build CSV string from multiple simulation results (batch export). */
function buildBatchExportCsv(results) {
    const summaryHeader = ['subscription_id', 'customer_id', 'simulation_start', 'simulation_end', 'renewals_count', 'ramps_applied', 'simulation_window', 'total_revenue', 'currency_code', 'chargebee_next_billing', 'hivesight_next_billing', 'validation_passed', 'validation_message', 'timezone'];
    const rows = [summaryHeader.join(',')];

    results.forEach(data => {
        const events = data.events || [];
        const renewals = events.filter(e => e.type === 'renewal').length;
        const ramps = events.filter(e => e.type === 'ramp_applied').length;
        const totalRevenue = events
            .filter(e => e.amount != null && e.amount !== undefined)
            .reduce((sum, e) => sum + e.amount, 0);
        const revDisplay = totalRevenue > 0 ? formatAmount(totalRevenue, data.currencyCode) : '—';
        const windowStr = data.simulationStart && data.simulationEnd
            ? `${formatDate(data.simulationStart)} → ${formatDate(data.simulationEnd)}`
            : '18 months';
        rows.push([
            data.subscriptionId ?? '',
            data.customerId ?? '',
            formatDate(data.simulationStart) ?? '',
            formatDate(data.simulationEnd) ?? '',
            renewals,
            ramps,
            windowStr,
            revDisplay,
            data.currencyCode ?? '',
            formatDate(data.chargebeeUiNextBilling) ?? '',
            formatDate(data.hivesightNextBilling) ?? '',
            data.validationPassed != null ? data.validationPassed : '',
            data.validationMessage ?? '',
            data.timezone ?? ''
        ].map(csvEscape).join(','));
    });

    rows.push('');
    rows.push(['subscription_id', 'event_date', 'event_type', 'description', 'amount_display', 'amount_minor', 'currency_code'].join(','));
    results.forEach(data => {
        const events = data.events || [];
        const subId = data.subscriptionId ?? '';
        events.forEach(e => {
            const amountDisplay = (e.amount != null && e.amount !== undefined) ? formatAmount(e.amount, e.currencyCode) : '';
            rows.push([
                subId,
                e.dateFormatted ?? formatDate(e.date) ?? '',
                e.type ?? '',
                e.description ?? '',
                amountDisplay,
                e.amount != null && e.amount !== undefined ? e.amount : '',
                e.currencyCode ?? ''
            ].map(csvEscape).join(','));
        });
    });
    return rows.join('\r\n');
}

/** Parse month label (e.g. "Feb 2026") to sortable key (YYYY-MM). */
function monthLabelToKey(label) {
    if (!label || typeof label !== 'string') return '';
    const parts = label.trim().split(/\s+/);
    if (parts.length < 2) return label;
    const months = { Jan: 1, Feb: 2, Mar: 3, Apr: 4, May: 5, Jun: 6, Jul: 7, Aug: 8, Sep: 9, Oct: 10, Nov: 11, Dec: 12 };
    const m = months[parts[0]];
    const y = parseInt(parts[1], 10);
    if (!m || !y) return label;
    return `${y}-${String(m).padStart(2, '0')}`;
}

/** Build CSV for SHEET 1 - Executive Summary: one row per subscription, month columns, Total (Range), Transitions, Risk Level. */
function buildBatchExecutiveSummaryCsv(results) {
    if (!results || results.length === 0) return '';

    const breakdownsBySub = new Map();
    const allMonthLabels = new Set();
    results.forEach(data => {
        const list = data.monthlyBreakdowns || [];
        breakdownsBySub.set(data.subscriptionId ?? '', list);
        list.forEach(b => { if (b.monthLabel) allMonthLabels.add(b.monthLabel); });
    });
    const sortedMonths = [...allMonthLabels].sort((a, b) => monthLabelToKey(a).localeCompare(monthLabelToKey(b)));

    const simulationStart = results[0]?.simulationStart;
    const simulationEnd = results[0]?.simulationEnd;
    const rangeStr = simulationStart && simulationEnd
        ? `${formatDate(simulationStart)} → ${formatDate(simulationEnd)}`
        : '—';
    const generatedOn = new Date().toISOString().slice(0, 10);

    const rows = [];
    rows.push('SHEET 1 - Summary (Executive View)');
    rows.push(`Simulation Range: ${rangeStr}`);
    rows.push(`Generated On: ${generatedOn}`);
    rows.push('');

    const header = ['Subscription', 'Contract End', ...sortedMonths, 'Total (Range)', 'Transitions', 'Risk Level'];
    rows.push(header.map(csvEscape).join(','));

    results.forEach(data => {
        const breakdowns = breakdownsBySub.get(data.subscriptionId ?? '') || [];
        const monthToTotal = new Map();
        let totalRange = 0;
        const allChanges = new Set();
        breakdowns.forEach(b => {
            if (b.monthLabel) {
                const totalCents = b.totalCents != null ? b.totalCents : 0;
                monthToTotal.set(b.monthLabel, totalCents);
                totalRange += totalCents;
                (b.changes || []).forEach(c => { if (c) allChanges.add(c); });
            }
        });

        const contractEnd = data.subscriptionEndDate
            ? formatDateLong(data.subscriptionEndDate, data.timezone)
            : '—';

        const cancelled = (data.events || []).some(e => e.type === 'cancelled');
        let riskLevel = 'Low';
        if (cancelled || allChanges.size > 2) riskLevel = 'High';
        else if (allChanges.size > 0) riskLevel = 'Medium';

        const transitions = allChanges.size > 0 ? [...allChanges].join(', ') : 'None';

        const currencyCode = data.currencyCode || 'USD';
        const row = [
            data.subscriptionId ?? '',
            contractEnd,
            ...sortedMonths.map(m => {
                const cents = monthToTotal.get(m);
                if (cents == null) return '';
                const s = formatAmount(cents, currencyCode);
                return s ? s.replace(/\$/g, '').trim() : cents;
            }),
            totalRange != null ? formatAmount(totalRange, currencyCode).replace(/\$/g, '').trim() : '',
            transitions,
            riskLevel
        ];
        rows.push(row.map(csvEscape).join(','));
    });

    return rows.join('\r\n');
}

/** Build CSV for SHEET 2 - Monthly Simulation (Detailed View): one row per subscription per month. */
function buildBatchMonthlyDetailedCsv(results) {
    if (!results || results.length === 0) return '';

    const generatedOn = new Date().toISOString().slice(0, 10);
    const rows = [];
    rows.push('SHEET 2 - Monthly Simulation (Detailed View)');
    rows.push(`Generated On: ${generatedOn}`);
    rows.push('');

    const header = ['Subscription', 'Month', 'Unit Price', 'Qty', 'Subtotal', 'Discount', 'Tax', 'Total', 'Proration', 'Change'];
    rows.push(header.map(csvEscape).join(','));

    results.forEach(data => {
        const breakdowns = data.monthlyBreakdowns || [];
        const currencyCode = data.currencyCode || 'USD';
        const subId = data.subscriptionId ?? '';

        breakdowns.forEach(b => {
            const hasProration = (b.changes || []).some(c => /proration|prorate/i.test(String(c)));
            const changeStr = (b.changes && b.changes.length > 0) ? b.changes.join('; ') : 'None';

            const stripCurrency = (s) => (s || '').replace(/\$/g, '').trim();
            const unitPriceDisplay = b.unitPrice != null ? stripCurrency(formatAmount(b.unitPrice, currencyCode)) : '';
            const subtotalDisplay = b.subtotalCents != null ? stripCurrency(formatAmount(b.subtotalCents, currencyCode)) : '';
            const discountVal = b.discountCents != null ? b.discountCents : 0;
            const discountDisplay = discountVal !== 0 ? (discountVal > 0 ? '-' : '') + stripCurrency(formatAmount(Math.abs(discountVal), currencyCode)) : '0';
            const taxDisplay = b.taxCents != null ? stripCurrency(formatAmount(b.taxCents, currencyCode)) : '';
            const totalDisplay = b.totalCents != null ? stripCurrency(formatAmount(b.totalCents, currencyCode)) : '';

            const row = [
                subId,
                b.monthLabel ?? '',
                unitPriceDisplay || (b.unitPrice != null ? b.unitPrice : ''),
                b.quantity != null ? b.quantity : '',
                subtotalDisplay || (b.subtotalCents != null ? b.subtotalCents : ''),
                discountDisplay,
                taxDisplay || (b.taxCents != null ? b.taxCents : ''),
                totalDisplay || (b.totalCents != null ? b.totalCents : ''),
                hasProration ? 'Yes' : 'No',
                changeStr
            ];
            rows.push(row.map(csvEscape).join(','));
        });
    });

    return rows.join('\r\n');
}

/** Trigger download of a blob as a file. */
function downloadBlob(blob, filename) {
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = filename;
    a.click();
    URL.revokeObjectURL(a.href);
}

/** Chart.js instances for batch charts; destroyed when switching to single or re-running batch. */
let batchRevenueChartInstance = null;
let batchOutcomeChartInstance = null;

function hideBatchCharts() {
    if (batchRevenueChartInstance) {
        batchRevenueChartInstance.destroy();
        batchRevenueChartInstance = null;
    }
    if (batchOutcomeChartInstance) {
        batchOutcomeChartInstance.destroy();
        batchOutcomeChartInstance = null;
    }
    document.getElementById('batchChartsSection').classList.add('d-none');
}

/** Render bar chart (revenue by subscription) and pie chart (cancelled vs active) for batch results. */
function renderBatchCharts(results) {
    if (!window.Chart || results.length === 0) return;
    hideBatchCharts();

    const revenueData = results.map(r => {
        const total = (r.events || [])
            .filter(e => e.amount != null && e.amount !== undefined)
            .reduce((sum, e) => sum + e.amount, 0);
        return { id: r.subscriptionId || '—', revenue: total, currencyCode: r.currencyCode || 'USD' };
    });
    const cancelledCount = results.filter(r => (r.events || []).some(e => e.type === 'cancelled')).length;
    const activeCount = results.length - cancelledCount;

    document.getElementById('batchChartsSection').classList.remove('d-none');

    const barCtx = document.getElementById('batchRevenueChart').getContext('2d');
    batchRevenueChartInstance = new Chart(barCtx, {
        type: 'bar',
        data: {
            labels: revenueData.map(d => d.id.length > 12 ? d.id.slice(0, 10) + '…' : d.id),
            datasets: [{
                label: 'Projected revenue',
                data: revenueData.map(d => d.revenue),
                backgroundColor: 'rgba(40, 167, 69, 0.7)',
                borderColor: 'rgba(40, 167, 69, 1)',
                borderWidth: 1
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: true,
            plugins: {
                legend: { display: false },
                tooltip: {
                    callbacks: {
                        label: function (ctx) {
                            const d = revenueData[ctx.dataIndex];
                            return formatAmount(d.revenue, d.currencyCode);
                        }
                    }
                }
            },
            scales: {
                x: { ticks: { maxRotation: 45, minRotation: 45 } },
                y: { beginAtZero: true }
            }
        }
    });

    const pieCtx = document.getElementById('batchOutcomeChart').getContext('2d');
    batchOutcomeChartInstance = new Chart(pieCtx, {
        type: 'doughnut',
        data: {
            labels: ['Active through window', 'End in cancellation'],
            datasets: [{
                data: [activeCount, cancelledCount],
                backgroundColor: ['rgba(40, 167, 69, 0.8)', 'rgba(220, 53, 69, 0.8)'],
                borderWidth: 1
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: true,
            plugins: {
                legend: { position: 'bottom' }
            }
        }
    });
}

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
        lastSimulationResult = data;
        lastBatchResults = null;
        hideBatchCharts();
        document.getElementById('btnExport').disabled = false;
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
        lastSimulationResult = data;
        lastBatchResults = null;
        hideBatchCharts();
        document.getElementById('btnExport').disabled = false;
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

document.getElementById('btnSimulateBatch').addEventListener('click', async () => {
    const imported = window.importedSubscriptions;
    if (!imported || imported.length === 0) {
        showError('Import a CSV with subscription IDs first.');
        return;
    }
    hideError();
    const loadingEl = document.getElementById('loading');
    const msgEl = document.getElementById('loadingMessage');
    loadingEl.classList.remove('d-none');
    const results = [];
    const errors = [];
    for (let i = 0; i < imported.length; i++) {
        const { subscription_id: id } = imported[i];
        msgEl.textContent = `Simulating ${i + 1} of ${imported.length}: ${id}...`;
        try {
            const res = await fetch(`${API_BASE}/simulate/${encodeURIComponent(id)}?months=18`);
            const data = await res.json();
            if (!res.ok) throw new Error(data.message || 'Simulation failed');
            results.push(data);
        } catch (e) {
            errors.push({ id, message: e.message });
        }
    }
    loadingEl.classList.add('d-none');
    lastBatchResults = results;
    lastSimulationResult = null; // Don't show any single-subscription report; Export will use batch only
    document.getElementById('btnExport').disabled = results.length === 0;
    // Don't render timeline or report in UI for batch — only Export shows the data
    document.getElementById('detailedReport').classList.add('d-none');
    document.getElementById('timeline').innerHTML = '';
    document.getElementById('validationResult').classList.add('d-none');
    document.getElementById('validationBadge').classList.add('d-none');
    if (results.length > 0) {
        renderBatchCharts(results);
    }
    if (errors.length > 0) {
        showError(`Batch completed. ${results.length} succeeded, ${errors.length} failed: ${errors.map(e => e.id + ': ' + e.message).join('; ')}`);
    } else if (results.length === 0) {
        showError('All simulations failed.');
    }
});

function getExportResults() {
    if (lastBatchResults && lastBatchResults.length > 0) return lastBatchResults;
    if (lastSimulationResult) return [lastSimulationResult];
    return null;
}

document.getElementById('exportExecutive').addEventListener('click', (e) => {
    e.preventDefault();
    const results = getExportResults();
    if (!results || results.length === 0) return;
    const csv = buildBatchExecutiveSummaryCsv(results);
    const dateStr = new Date().toISOString().slice(0, 10);
    const filename = results.length > 1
        ? `hivesight_batch_executive_${results.length}_subscriptions_${dateStr}.csv`
        : `hivesight_${(results[0].subscriptionId || 'export').replace(/[^a-zA-Z0-9-_]/g, '_')}_executive_${dateStr}.csv`;
    downloadBlob(new Blob([csv], { type: 'text/csv;charset=utf-8' }), filename);
});

document.getElementById('exportMonthly').addEventListener('click', (e) => {
    e.preventDefault();
    const results = getExportResults();
    if (!results || results.length === 0) return;
    const csv = buildBatchMonthlyDetailedCsv(results);
    const dateStr = new Date().toISOString().slice(0, 10);
    const filename = results.length > 1
        ? `hivesight_batch_monthly_detailed_${results.length}_subscriptions_${dateStr}.csv`
        : `hivesight_${(results[0].subscriptionId || 'export').replace(/[^a-zA-Z0-9-_]/g, '_')}_monthly_detailed_${dateStr}.csv`;
    downloadBlob(new Blob([csv], { type: 'text/csv;charset=utf-8' }), filename);
});

document.getElementById('btnImport').addEventListener('click', () => {
    document.getElementById('fileImport').click();
});

document.getElementById('fileImport').addEventListener('change', (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
        try {
            const text = reader.result;
            const lines = text.split(/\r?\n/).filter(l => l.trim());
            if (lines.length < 2) {
                showError('CSV must have a header row and at least one data row.');
                e.target.value = '';
                return;
            }
            function parseCsvRow(line) {
                const out = [];
                let cur = '';
                let inQuotes = false;
                for (let i = 0; i < line.length; i++) {
                    const c = line[i];
                    if (c === '"') inQuotes = !inQuotes;
                    else if (c === ',' && !inQuotes) { out.push(cur.trim().replace(/^"|"$/g, '').replace(/""/g, '"')); cur = ''; }
                    else cur += c;
                }
                out.push(cur.trim().replace(/^"|"$/g, '').replace(/""/g, '"'));
                return out;
            }
            const header = parseCsvRow(lines[0]).map(h => h.toLowerCase().trim());
            const subIdIdx = header.findIndex(h => h === 'subscription_id' || h === 'subscription id');
            const expectedIdx = header.findIndex(h => h === 'expected_cancel_date' || h === 'expected cancel date' || h === 'expected_cancel' || h === 'expected cancel');
            if (subIdIdx < 0) {
                showError('CSV must have a "subscription_id" column.');
                e.target.value = '';
                return;
            }
            const rows = [];
            for (let i = 1; i < lines.length; i++) {
                const cells = parseCsvRow(lines[i]);
                const subId = (cells[subIdIdx] || '').trim().replace(/^"|"$/g, '');
                if (!subId) continue;
                const expected = expectedIdx >= 0 ? (cells[expectedIdx] || '').trim().replace(/^"|"$/g, '') : '';
                rows.push({ subscription_id: subId, expected_cancel_date: expected });
            }
            if (rows.length === 0) {
                showError('No valid subscription_id values found in CSV.');
                e.target.value = '';
                return;
            }
            hideError();
            window.importedSubscriptions = rows;
            document.getElementById('btnSimulateBatch').disabled = false;
            const listEl = document.getElementById('importedList');
            const itemsEl = document.getElementById('importedListItems');
            itemsEl.innerHTML = rows.map((r, i) => `
                <div class="subscription-option imported-option" data-id="${r.subscription_id}" data-expected="${r.expected_cancel_date || ''}">
                    <strong>${r.subscription_id}</strong>${r.expected_cancel_date ? ` — cancel: ${r.expected_cancel_date}` : ''}
                </div>
            `).join('');
            listEl.classList.remove('d-none');
            itemsEl.querySelectorAll('.imported-option').forEach(el => {
                el.addEventListener('click', () => {
                    itemsEl.querySelectorAll('.imported-option').forEach(x => x.classList.remove('selected'));
                    el.classList.add('selected');
                    document.getElementById('subscriptionId').value = el.dataset.id;
                    document.getElementById('expectedCancel').value = el.dataset.expected || '';
                });
            });
        } catch (err) {
            showError('Invalid CSV: ' + err.message);
        }
        e.target.value = '';
    };
    reader.readAsText(file, 'UTF-8');
});
