(() => {
	const ROW_HEIGHT_PX = 48;
	const DAY_MINUTES = 24 * 60;
	const DRAG_SNAP_MINUTES = 15;
	const DRAG_THRESHOLD_PX = 6;

	const config = window.__APP_CONFIG__ || {};
	const apiBaseUrl = (config.apiBaseUrl || "").replace(/\/+$/, "");

	const tg = window.Telegram && window.Telegram.WebApp ? window.Telegram.WebApp : null;
	if (tg && typeof tg.ready === "function") {
		tg.ready();
	}
	document.documentElement.classList.toggle("tg", Boolean(tg));
	document.documentElement.classList.toggle(
		"ios",
		Boolean(tg && typeof tg.platform === "string" && tg.platform.toLowerCase() === "ios")
	);
	if (tg && typeof tg.expand === "function") {
		try {
			tg.expand();
		} catch {
			// ignore
		}
	}

	function updateTelegramViewportCssVars() {
		const stable = tg && typeof tg.viewportStableHeight === "number" ? tg.viewportStableHeight : 0;
		const fallback = typeof window.innerHeight === "number" ? window.innerHeight : 0;
		const h = stable > 0 ? stable : fallback;
		if (h > 0) {
			document.documentElement.style.setProperty("--tg-viewport-height", `${Math.round(h)}px`);
		}
	}

	if (tg) {
		updateTelegramViewportCssVars();
		if (typeof tg.onEvent === "function") {
			try {
				tg.onEvent("viewportChanged", updateTelegramViewportCssVars);
			} catch {
				// ignore
			}
		}
		window.addEventListener("resize", updateTelegramViewportCssVars, { passive: true });
	}

	const el = {
		grid: document.getElementById("calendarGrid"),
		gridHead: document.getElementById("gridHead"),
		gridBody: document.getElementById("gridBody"),
		rangeLabel: document.getElementById("rangeLabel"),
		prevBtn: document.getElementById("prevBtn"),
		nextBtn: document.getElementById("nextBtn"),
		todayBtn: document.getElementById("todayBtn"),
		newBtn: document.getElementById("newBtn"),
		reloadBtn: document.getElementById("reloadBtn"),
		userName: document.getElementById("userName"),
		userId: document.getElementById("userId"),
		syncStatus: document.getElementById("syncStatus"),
		nowClock: document.getElementById("nowClock"),
		pills: Array.from(document.querySelectorAll(".pill[data-view]")),
		modal: document.getElementById("eventModal"),
		modalTitle: document.getElementById("modalTitle"),
		closeModal: document.getElementById("closeModal"),
		eventTitle: document.getElementById("eventTitle"),
		eventStart: document.getElementById("eventStart"),
		eventEnd: document.getElementById("eventEnd"),
		eventLocation: document.getElementById("eventLocation"),
		eventLink: document.getElementById("eventLink"),
		deleteBtn: document.getElementById("deleteBtn"),
		saveBtn: document.getElementById("saveBtn")
	};

	const state = {
		view: "week",
		weekStart: startOfWeek(new Date()),
		monthStart: startOfMonth(new Date()),
		meetings: [],
		editingId: null,
		nowTimer: null,
		dragSuppressClickUntil: 0
	};

	init();

	function init() {
		hydrateUser();
		bindUi();
		render();
		scheduleNowClockAndLine();
	}

	function hydrateUser() {
		try {
			if (tg && tg.initDataUnsafe && tg.initDataUnsafe.user) {
				const u = tg.initDataUnsafe.user;
				el.userName.textContent = u.first_name || u.username || "Вы";
				el.userId.textContent = u.id ? `id ${u.id}` : "";
			} else {
				const telegramId = getTelegramIdFromUrl();
				el.userName.textContent = "Вы";
				el.userId.textContent = telegramId ? `id ${telegramId}` : "";
			}
		} catch {
			// ignore
		}
	}

	function bindUi() {
		let syncingGridScroll = false;
		const syncGridScroll = (source) => {
			if (syncingGridScroll) return;
			syncingGridScroll = true;
			try {
				if (source === "body") {
					el.gridHead.scrollLeft = el.gridBody.scrollLeft;
				} else {
					el.gridBody.scrollLeft = el.gridHead.scrollLeft;
				}
			} finally {
				syncingGridScroll = false;
			}
		};

		el.gridBody.addEventListener(
			"scroll",
			() => {
				syncGridScroll("body");
			},
			{ passive: true }
		);
		el.gridHead.addEventListener(
			"scroll",
			() => {
				syncGridScroll("head");
			},
			{ passive: true }
		);

		el.prevBtn.addEventListener("click", () => {
			if (state.view === "week") {
				state.weekStart = addDays(state.weekStart, -7);
			} else {
				state.monthStart = addMonths(state.monthStart, -1);
			}
			render();
		});

		el.nextBtn.addEventListener("click", () => {
			if (state.view === "week") {
				state.weekStart = addDays(state.weekStart, 7);
			} else {
				state.monthStart = addMonths(state.monthStart, 1);
			}
			render();
		});

		el.todayBtn.addEventListener("click", () => {
			const now = new Date();
			state.weekStart = startOfWeek(now);
			state.monthStart = startOfMonth(now);
			render();
		});

		el.reloadBtn.addEventListener("click", () => {
			render();
		});

		el.newBtn.addEventListener("click", () => {
			openCreateModal();
		});

		el.pills.forEach((pill) => {
			pill.addEventListener("click", () => {
				const view = pill.getAttribute("data-view");
				if (!view || view === state.view) return;
				state.view = view;
				if (view === "month") {
					state.monthStart = startOfMonth(state.weekStart);
				} else {
					state.weekStart = startOfWeek(state.monthStart);
				}
				el.pills.forEach((p) => p.classList.toggle("active", p === pill));
				render();
			});
		});

		el.closeModal.addEventListener("click", closeModal);
		el.modal.addEventListener("click", (e) => {
			if (e.target === el.modal) closeModal();
		});

		el.saveBtn.addEventListener("click", async () => {
			await saveMeetingFromModal();
		});

		el.deleteBtn.addEventListener("click", async () => {
			await deleteMeetingFromModal();
		});
	}

	async function render() {
		if (state.view === "week") {
			el.grid.classList.remove("month-mode");
			renderWeekGrid();
			const range = getWeekRange();
			await loadMeetings(range.from, range.to);
			renderWeekEvents();
		} else {
			el.grid.classList.add("month-mode");
			renderMonthGrid();
			const range = getMonthRange();
			await loadMeetings(range.from, range.to);
			renderMonthEvents();
		}
	}

	function renderWeekGrid() {
		const start = new Date(state.weekStart);
		const end = addDays(start, 6);
		el.rangeLabel.textContent = formatRange(start, end);

		const today = startOfDay(new Date());
		const todayIndex = sameDayRangeIndex(start, today);

		el.gridHead.innerHTML = "";
		const headBlank = document.createElement("div");
		headBlank.className = "head-cell";
		el.gridHead.appendChild(headBlank);

		for (let d = 0; d < 7; d++) {
			const day = addDays(start, d);
			const cell = document.createElement("div");
			cell.className = "head-cell";
			if (sameDay(day, today)) {
				cell.classList.add("today");
			}
			const inner = document.createElement("div");
			inner.className = "head-day";
			inner.innerHTML = `<div class="head-main"><span class="day-num">${day.getDate()}</span><span class="day-dow">, ${formatDow(day)}</span></div>`;
			cell.appendChild(inner);
			el.gridHead.appendChild(cell);
		}

		el.gridBody.innerHTML = "";
		for (let hour = 0; hour < 24; hour++) {
			const timeCell = document.createElement("div");
			timeCell.className = "grid-cell time-cell";
			timeCell.textContent = `${pad2(hour)}:00`;
			el.gridBody.appendChild(timeCell);

			for (let d = 0; d < 7; d++) {
				const cell = document.createElement("div");
				cell.className = "grid-cell";
				if (todayIndex === d) {
					cell.classList.add("today");
				}
				el.gridBody.appendChild(cell);
			}
		}

		for (let d = 0; d < 7; d++) {
			const layer = document.createElement("div");
			layer.className = "day-layer";
			layer.dataset.dayIndex = String(d);
			layer.style.gridRow = "1 / span 24";
			layer.style.gridColumn = String(2 + d);
			el.gridBody.appendChild(layer);
		}

		const nowLayer = document.createElement("div");
		nowLayer.className = "now-layer";
		nowLayer.style.gridRow = "1 / span 24";
		nowLayer.style.gridColumn = "2 / span 7";
		nowLayer.id = "nowLayer";
		el.gridBody.appendChild(nowLayer);

		updateNowLine();
		scrollToNowIfVisible();
	}

	function renderMonthGrid() {
		const monthStart = startOfMonth(state.monthStart);
		el.rangeLabel.textContent = formatMonthTitle(monthStart);

		el.gridHead.innerHTML = "";
		const names = ["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"];
		for (const name of names) {
			const cell = document.createElement("div");
			cell.className = "month-head-cell";
			cell.textContent = name;
			el.gridHead.appendChild(cell);
		}

		el.gridBody.innerHTML = "";
		const start = startOfWeek(monthStart);
		const today = startOfDay(new Date());

		for (let i = 0; i < 42; i++) {
			const day = addDays(start, i);
			const cell = document.createElement("div");
			cell.className = "month-cell";
			cell.dataset.date = formatDateISO(day);
			if (day.getMonth() !== monthStart.getMonth()) {
				cell.classList.add("out-month");
			}
			if (sameDay(day, today)) {
				cell.classList.add("today");
			}

			const dayNumber = document.createElement("div");
			dayNumber.className = "month-day-number";
			dayNumber.textContent = String(day.getDate());
			cell.appendChild(dayNumber);

			const list = document.createElement("div");
			list.className = "month-events";
			cell.appendChild(list);

			cell.addEventListener("click", (e) => {
				if (e.target instanceof HTMLElement && e.target.closest(".month-event-chip")) {
					return;
				}
				openCreateModal(day);
			});

			el.gridBody.appendChild(cell);
		}
	}

	function renderMonthEvents() {
		const byIso = new Map();
		Array.from(el.gridBody.querySelectorAll(".month-cell")).forEach((cell) => {
			const key = cell.dataset.date;
			if (!key) return;
			byIso.set(key, cell);
			const list = cell.querySelector(".month-events");
			if (list) list.innerHTML = "";
		});

		const overflow = new Map();
		for (const m of state.meetings) {
			const start = new Date(m.startsAt);
			const key = formatDateISO(start);
			const cell = byIso.get(key);
			if (!cell) continue;
			const list = cell.querySelector(".month-events");
			if (!list) continue;

			const used = list.children.length;
			if (used >= 3) {
				overflow.set(key, (overflow.get(key) || 0) + 1);
				continue;
			}

			const chip = document.createElement("button");
			chip.type = "button";
			chip.className = "month-event-chip";
			chip.textContent = `${formatHm(start)} ${m.title || "(без названия)"}`;
			chip.title = m.title || "";
			chip.addEventListener("click", (e) => {
				e.stopPropagation();
				openEditModal(m);
			});
			list.appendChild(chip);
		}

		for (const [key, count] of overflow.entries()) {
			const cell = byIso.get(key);
			if (!cell) continue;
			const list = cell.querySelector(".month-events");
			if (!list) continue;
			const more = document.createElement("div");
			more.className = "month-more";
			more.textContent = `+${count} еще`;
			list.appendChild(more);
		}
	}

	async function loadMeetings(from, to) {
		if (!apiBaseUrl) {
			el.syncStatus.textContent = "Не настроен API (config.js)";
			state.meetings = [];
			return [];
		}

		const url = new URL(apiBaseUrl + "/api/miniapp/meetings");
		url.searchParams.set("from", from);
		url.searchParams.set("to", to);

		const telegramId = getTelegramIdFromUrl();
		const initData = tg && typeof tg.initData === "string" ? tg.initData : "";
		if (!initData && telegramId) {
			url.searchParams.set("telegramId", String(telegramId));
		}

		const headers = {};
		if (initData) {
			headers["X-Telegram-Init-Data"] = initData;
		}

		try {
			el.syncStatus.textContent = "Загрузка...";
			const res = await fetch(url.toString(), { headers });
			if (res.status === 401) {
				el.syncStatus.textContent = "Нет доступа (Unauthorized)";
				state.meetings = [];
				return [];
			}
			if (!res.ok) {
				const txt = await res.text().catch(() => "");
				el.syncStatus.textContent = `Ошибка API (${res.status})`;
				console.warn("API error", res.status, txt);
				state.meetings = [];
				return [];
			}
			const data = await res.json();
			state.meetings = Array.isArray(data) ? data : [];
			el.syncStatus.textContent = "Синхронизация включена";
			return state.meetings;
		} catch (e) {
			el.syncStatus.textContent = "Ошибка сети";
			console.warn(e);
			state.meetings = [];
			return [];
		}
	}

	function renderWeekEvents() {
		const rowHeightPx = getHourRowHeightPx();
		const layers = Array.from(el.gridBody.querySelectorAll(".day-layer"));
		layers.forEach((l) => {
			l.innerHTML = "";
		});

		const start = startOfDay(state.weekStart);
		const dayBuckets = new Array(7).fill(0).map(() => []);

		for (const m of state.meetings) {
			const startDt = new Date(m.startsAt);
			const endDt = new Date(m.endsAt);
			for (let d = 0; d < 7; d++) {
				const day = addDays(start, d);
				const dayStart = day;
				const dayEnd = addDays(dayStart, 1);
				const s = clampDate(startDt, dayStart, dayEnd);
				const e = clampDate(endDt, dayStart, dayEnd);
				if (e <= dayStart || s >= dayEnd) continue;
				const startMin = Math.max(0, minutesFromDayStart(s, dayStart));
				const endMin = Math.min(DAY_MINUTES, minutesFromDayStart(e, dayStart));
				dayBuckets[d].push({
					id: m.id,
					title: m.title || "(без названия)",
					location: m.location || "",
					externalLink: m.externalLink || "",
					startsAt: m.startsAt,
					endsAt: m.endsAt,
					startMin,
					endMin
				});
			}
		}

		dayBuckets.forEach((events, dayIndex) => {
			const layer = layers.find((l) => Number(l.dataset.dayIndex) === dayIndex);
			if (!layer) return;
			renderDayEvents(layer, events, dayIndex, rowHeightPx);
		});

		updateNowLine();
	}

	function renderDayEvents(layer, events, dayIndex, rowHeightPx) {
		const sorted = events.slice().sort((a, b) => a.startMin - b.startMin || a.endMin - b.endMin);
		const active = [];
		const assigned = [];
		let maxLanes = 1;

		for (const ev of sorted) {
			for (let i = active.length - 1; i >= 0; i--) {
				if (active[i].endMin <= ev.startMin) active.splice(i, 1);
			}

			const used = new Set(active.map((a) => a.lane));
			let lane = 0;
			while (used.has(lane)) lane++;

			active.push({ endMin: ev.endMin, lane });
			maxLanes = Math.max(maxLanes, active.length, lane + 1);
			assigned.push({ ...ev, lane });
		}

		for (const ev of assigned) {
			const top = (ev.startMin / 60) * rowHeightPx;
			const height = Math.max(
				22,
				((Math.max(ev.endMin, ev.startMin + 10) - ev.startMin) / 60) * rowHeightPx
			);
			const widthPercent = 100 / maxLanes;
			const leftPercent = ev.lane * widthPercent;

			const node = document.createElement("div");
			node.className = "event";
			node.style.top = `${top + 2}px`;
			node.style.height = `${height - 4}px`;
			node.style.left = `calc(${leftPercent}% + 6px)`;
			node.style.width = `calc(${widthPercent}% - 12px)`;
			node.innerHTML = `<div class="event-title">${escapeHtml(ev.title)}</div><div class="event-meta">${formatTimeRangeFromMinutes(ev.startMin, ev.endMin)}${ev.location ? " • " + escapeHtml(ev.location) : ""}</div>`;
			const resizeHandle = document.createElement("div");
			resizeHandle.className = "event-resize";
			node.appendChild(resizeHandle);

			node.addEventListener("click", () => {
				if (Date.now() < state.dragSuppressClickUntil) return;
				openEditModal(ev);
			});

			bindEventDrag(node, ev, dayIndex);
			bindEventResize(node, resizeHandle, ev);
			layer.appendChild(node);
		}
	}

	function bindEventDrag(node, ev, dayIndex) {
		let pointerId = null;
		let startX = 0;
		let startY = 0;
		let moved = false;
		let rowHeightPx = ROW_HEIGHT_PX;

		node.addEventListener("pointerdown", (e) => {
			if (e.button !== 0) return;
			pointerId = e.pointerId;
			startX = e.clientX;
			startY = e.clientY;
			moved = false;
			rowHeightPx = getHourRowHeightPx();
			node.setPointerCapture(pointerId);
			node.classList.add("drag-ready");
			e.preventDefault();
		});

		node.addEventListener("pointermove", (e) => {
			if (pointerId !== e.pointerId) return;
			const dx = e.clientX - startX;
			const dy = e.clientY - startY;
			if (!moved && Math.hypot(dx, dy) >= DRAG_THRESHOLD_PX) {
				moved = true;
				node.classList.add("dragging");
			}
			if (!moved) return;
			node.style.transform = `translate(${dx}px, ${dy}px)`;
		});

		node.addEventListener("pointerup", async (e) => {
			if (pointerId !== e.pointerId) return;
			const dx = e.clientX - startX;
			const dy = e.clientY - startY;
			cleanupDragVisual(node);
			pointerId = null;

			if (!moved) return;
			state.dragSuppressClickUntil = Date.now() + 350;

			const dayWidth = node.parentElement ? node.parentElement.getBoundingClientRect().width : 1;
			const deltaDays = Math.round(dx / Math.max(dayWidth, 1));
			const rawMinutes = (dy / Math.max(1, rowHeightPx)) * 60;
			const deltaMinutes = Math.round(rawMinutes / DRAG_SNAP_MINUTES) * DRAG_SNAP_MINUTES;
			if (deltaDays === 0 && deltaMinutes === 0) return;

			await moveMeetingByDrag(ev, dayIndex, deltaDays, deltaMinutes);
		});

		node.addEventListener("pointercancel", () => {
			cleanupDragVisual(node);
			pointerId = null;
		});
	}

	function cleanupDragVisual(node) {
		node.classList.remove("drag-ready", "dragging");
		node.style.transform = "";
	}

	function bindEventResize(node, handle, ev) {
		let pointerId = null;
		let startY = 0;
		let baseHeightPx = 0;
		let moved = false;
		let previewDeltaMinutes = 0;
		let rowHeightPx = ROW_HEIGHT_PX;

		handle.addEventListener("pointerdown", (e) => {
			if (e.button !== 0) return;
			pointerId = e.pointerId;
			startY = e.clientY;
			baseHeightPx = node.getBoundingClientRect().height;
			moved = false;
			previewDeltaMinutes = 0;
			rowHeightPx = getHourRowHeightPx();
			handle.setPointerCapture(pointerId);
			node.classList.add("resizing");
			e.preventDefault();
			e.stopPropagation();
		});

		handle.addEventListener("pointermove", (e) => {
			if (pointerId !== e.pointerId) return;
			const dy = e.clientY - startY;
			if (!moved && Math.abs(dy) >= DRAG_THRESHOLD_PX) {
				moved = true;
			}
			const rawMinutes = (dy / Math.max(1, rowHeightPx)) * 60;
			const deltaMinutes = Math.round(rawMinutes / DRAG_SNAP_MINUTES) * DRAG_SNAP_MINUTES;
			previewDeltaMinutes = deltaMinutes;
			if (!moved) return;

			const nextHeight = Math.max(
				22,
				baseHeightPx + (deltaMinutes / 60) * rowHeightPx
			);
			node.style.height = `${nextHeight}px`;
		});

		handle.addEventListener("pointerup", async (e) => {
			if (pointerId !== e.pointerId) return;
			const deltaMinutes = previewDeltaMinutes;
			handle.releasePointerCapture(pointerId);
			pointerId = null;
			node.classList.remove("resizing");
			node.style.height = "";

			if (!moved || deltaMinutes === 0) return;
			state.dragSuppressClickUntil = Date.now() + 350;
			await resizeMeetingByHandle(ev, deltaMinutes);
		});

		handle.addEventListener("pointercancel", () => {
			if (pointerId != null) {
				try {
					handle.releasePointerCapture(pointerId);
				} catch {
					// ignore
				}
			}
			pointerId = null;
			node.classList.remove("resizing");
			node.style.height = "";
		});
	}

	async function moveMeetingByDrag(ev, dayIndex, deltaDays, deltaMinutes) {
		if (!apiBaseUrl) return;

		const originalStart = new Date(ev.startsAt);
		const originalEnd = new Date(ev.endsAt);
		if (Number.isNaN(originalStart.getTime()) || Number.isNaN(originalEnd.getTime())) return;

		const currentDayStart = addDays(startOfDay(state.weekStart), dayIndex);
		const originalDayStart = startOfDay(originalStart);
		const dayCorrection = Math.round((currentDayStart.getTime() - originalDayStart.getTime()) / 86400000);

		const totalDayShift = dayCorrection + deltaDays;
		const nextStart = addMinutes(addDays(originalStart, totalDayShift), deltaMinutes);
		const nextEnd = addMinutes(addDays(originalEnd, totalDayShift), deltaMinutes);

		if (nextEnd.getTime() <= nextStart.getTime()) return;

		const ok = await patchMeeting(ev.id, {
			startsAt: toOffsetIso(nextStart),
			endsAt: toOffsetIso(nextEnd)
		});
		if (!ok) return;

		await render();
	}

	async function resizeMeetingByHandle(ev, deltaMinutes) {
		if (!apiBaseUrl) return;
		const start = new Date(ev.startsAt);
		const end = new Date(ev.endsAt);
		if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) return;

		const minDurationMinutes = 15;
		const currentDurationMinutes = Math.round((end.getTime() - start.getTime()) / 60000);
		const nextDurationMinutes = Math.max(minDurationMinutes, currentDurationMinutes + deltaMinutes);
		const nextEnd = addMinutes(start, nextDurationMinutes);

		const ok = await patchMeeting(ev.id, {
			endsAt: toOffsetIso(nextEnd)
		});
		if (!ok) return;
		await render();
	}

	function scheduleNowClockAndLine() {
		if (state.nowTimer) clearInterval(state.nowTimer);
		state.nowTimer = setInterval(() => {
			updateNowClock();
			if (state.view === "week") {
				updateNowLine();
			}
		}, 60 * 1000);
		updateNowClock();
	}

	function updateNowClock() {
		if (!el.nowClock) return;
		const now = new Date();
		el.nowClock.textContent = `${pad2(now.getHours())}:${pad2(now.getMinutes())}`;
	}

	function updateNowLine() {
		const nowLayer = document.getElementById("nowLayer");
		if (!nowLayer) return;
		nowLayer.innerHTML = "";

		const now = new Date();
		const weekStart = startOfDay(state.weekStart);
		const weekEnd = addDays(weekStart, 7);
		if (now < weekStart || now >= weekEnd) return;

		const dayIndex = Math.floor((startOfDay(now).getTime() - weekStart.getTime()) / 86400000);
		if (dayIndex < 0 || dayIndex > 6) return;

		const minutes = now.getHours() * 60 + now.getMinutes();
		const top = (minutes / 60) * getHourRowHeightPx();

		const line = document.createElement("div");
		line.className = "now-line";
		line.style.top = `${top}px`;
		line.style.left = `calc(${(100 / 7) * dayIndex}% )`;
		line.style.width = `calc(${100 / 7}% )`;

		const dot = document.createElement("div");
		dot.className = "now-dot";
		const label = document.createElement("div");
		label.className = "now-label";
		label.textContent = `${pad2(now.getHours())}:${pad2(now.getMinutes())}`;
		line.appendChild(dot);
		line.appendChild(label);
		nowLayer.appendChild(line);
	}

	function scrollToNowIfVisible() {
		if (state.view !== "week") return;
		const now = new Date();
		const weekStart = startOfDay(state.weekStart);
		const weekEnd = addDays(weekStart, 7);
		if (now < weekStart || now >= weekEnd) return;
		const minutes = now.getHours() * 60 + now.getMinutes();
		const top = (minutes / 60) * getHourRowHeightPx();
		el.gridBody.scrollTop = Math.max(0, top - 120);
	}

	function getHourRowHeightPx() {
		const cell = el.gridBody.querySelector(".time-cell");
		if (!cell) return ROW_HEIGHT_PX;
		const h = cell.getBoundingClientRect().height;
		return Number.isFinite(h) && h > 0 ? h : ROW_HEIGHT_PX;
	}

	function getWeekRange() {
		return {
			from: formatDateISO(state.weekStart),
			to: formatDateISO(addDays(state.weekStart, 6))
		};
	}

	function getMonthRange() {
		const from = startOfWeek(startOfMonth(state.monthStart));
		return {
			from: formatDateISO(from),
			to: formatDateISO(addDays(from, 41))
		};
	}

	function sameDayRangeIndex(weekStartDay, day) {
		const a = startOfDay(weekStartDay).getTime();
		const b = startOfDay(day).getTime();
		const diffDays = Math.floor((b - a) / 86400000);
		return diffDays >= 0 && diffDays < 7 ? diffDays : -1;
	}

	function openCreateModal(seedDate = null) {
		if (!apiBaseUrl) {
			alert("API не настроен. Укажи PUBLIC_API_BASE_URL для mini app.");
			return;
		}
		state.editingId = null;
		el.modalTitle.textContent = "Новое событие";
		el.deleteBtn.style.visibility = "hidden";

		const base = seedDate ? new Date(seedDate) : new Date();
		const start = roundToNext15(base);
		const end = addMinutes(start, 60);

		el.eventTitle.value = "";
		el.eventStart.value = toDatetimeLocalValue(start);
		el.eventEnd.value = toDatetimeLocalValue(end);
		el.eventLocation.value = "";
		el.eventLink.value = "";

		openModal();
	}

	function openEditModal(ev) {
		state.editingId = ev.id;
		el.modalTitle.textContent = "Событие";
		el.deleteBtn.style.visibility = "visible";

		el.eventTitle.value = ev.title || "";
		el.eventStart.value = toDatetimeLocalValue(ev.startsAt);
		el.eventEnd.value = toDatetimeLocalValue(ev.endsAt);
		el.eventLocation.value = ev.location || "";
		el.eventLink.value = ev.externalLink || "";

		openModal();
	}

	function openModal() {
		el.modal.classList.remove("hidden");
		setTimeout(() => {
			if (el.eventTitle) el.eventTitle.focus();
		}, 0);
	}

	function closeModal() {
		el.modal.classList.add("hidden");
	}

	async function saveMeetingFromModal() {
		if (!apiBaseUrl) {
			alert("API не настроен. Укажи PUBLIC_API_BASE_URL для mini app.");
			return;
		}

		const payload = {
			title: (el.eventTitle.value || "").trim(),
			startsAt: toOffsetIsoFromInput(el.eventStart.value),
			endsAt: toOffsetIsoFromInput(el.eventEnd.value),
			location: (el.eventLocation.value || "").trim() || null,
			externalLink: (el.eventLink.value || "").trim() || null
		};

		if (!payload.title || !payload.startsAt || !payload.endsAt) {
			alert("Заполните: название, начало и конец");
			return;
		}
		if (new Date(payload.endsAt).getTime() <= new Date(payload.startsAt).getTime()) {
			alert("Время окончания должно быть позже начала");
			return;
		}

		let ok;
		if (state.editingId) {
			ok = await patchMeeting(state.editingId, payload);
		} else {
			ok = await createMeeting(payload);
		}
		if (!ok) return;

		closeModal();
		await render();
	}

	async function deleteMeetingFromModal() {
		if (!apiBaseUrl || !state.editingId) return;
		if (!confirm("Удалить событие?")) return;

		const telegramId = getTelegramIdFromUrl();
		const initData = tg && typeof tg.initData === "string" ? tg.initData : "";
		const headers = {};
		if (initData) headers["X-Telegram-Init-Data"] = initData;

		try {
			const url = new URL(apiBaseUrl + `/api/miniapp/meetings/${state.editingId}`);
			if (!initData && telegramId) {
				url.searchParams.set("telegramId", String(telegramId));
			}
			const res = await fetch(url.toString(), { method: "DELETE", headers });
			if (!(res.status === 204 || res.ok)) {
				const txt = await res.text().catch(() => "");
				alert(`Ошибка удаления: ${res.status}\n${txt}`);
				return;
			}
			closeModal();
			await render();
		} catch (e) {
			console.warn(e);
			alert("Ошибка сети при удалении");
		}
	}

	async function createMeeting(payload) {
		const telegramId = getTelegramIdFromUrl();
		const initData = tg && typeof tg.initData === "string" ? tg.initData : "";
		const headers = { "Content-Type": "application/json" };
		if (initData) headers["X-Telegram-Init-Data"] = initData;

		try {
			const url = new URL(apiBaseUrl + "/api/miniapp/meetings");
			if (!initData && telegramId) {
				url.searchParams.set("telegramId", String(telegramId));
			}
			const res = await fetch(url.toString(), {
				method: "POST",
				headers,
				body: JSON.stringify(payload)
			});
			if (!res.ok) {
				const txt = await res.text().catch(() => "");
				alert(`Ошибка сохранения: ${res.status}\n${txt}`);
				return false;
			}
			return true;
		} catch (e) {
			console.warn(e);
			alert("Ошибка сети при сохранении");
			return false;
		}
	}

	async function patchMeeting(id, payload) {
		const telegramId = getTelegramIdFromUrl();
		const initData = tg && typeof tg.initData === "string" ? tg.initData : "";
		const headers = { "Content-Type": "application/json" };
		if (initData) headers["X-Telegram-Init-Data"] = initData;

		try {
			el.syncStatus.textContent = "Сохраняю...";
			const url = new URL(apiBaseUrl + `/api/miniapp/meetings/${id}`);
			if (!initData && telegramId) {
				url.searchParams.set("telegramId", String(telegramId));
			}
			const res = await fetch(url.toString(), {
				method: "PATCH",
				headers,
				body: JSON.stringify(payload)
			});
			if (!res.ok) {
				const txt = await res.text().catch(() => "");
				alert(`Ошибка сохранения: ${res.status}\n${txt}`);
				el.syncStatus.textContent = `Ошибка API (${res.status})`;
				return false;
			}
			el.syncStatus.textContent = "Синхронизация включена";
			return true;
		} catch (e) {
			console.warn(e);
			alert("Ошибка сети при сохранении");
			el.syncStatus.textContent = "Ошибка сети";
			return false;
		}
	}

	function getTelegramIdFromUrl() {
		try {
			const p = new URLSearchParams(location.search);
			const v = p.get("telegramId") || p.get("tg") || "";
			const n = Number(v);
			return Number.isFinite(n) && n > 0 ? n : null;
		} catch {
			return null;
		}
	}

	function startOfDay(d) {
		const x = new Date(d);
		x.setHours(0, 0, 0, 0);
		return x;
	}

	function startOfWeek(d) {
		const x = startOfDay(d);
		const day = (x.getDay() + 6) % 7;
		return addDays(x, -day);
	}

	function startOfMonth(d) {
		const x = startOfDay(d);
		x.setDate(1);
		return x;
	}

	function addDays(d, days) {
		const x = new Date(d);
		x.setDate(x.getDate() + days);
		return x;
	}

	function addMonths(d, months) {
		const x = new Date(d);
		x.setDate(1);
		x.setMonth(x.getMonth() + months);
		return startOfMonth(x);
	}

	function addMinutes(d, minutes) {
		const x = new Date(d);
		x.setMinutes(x.getMinutes() + minutes);
		return x;
	}

	function clampDate(date, min, max) {
		const t = date.getTime();
		return new Date(Math.min(Math.max(t, min.getTime()), max.getTime()));
	}

	function minutesFromDayStart(date, dayStart) {
		return Math.round((date.getTime() - dayStart.getTime()) / 60000);
	}

	function sameDay(a, b) {
		return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
	}

	function formatRange(start, end) {
		const sameMonth = start.getMonth() === end.getMonth() && start.getFullYear() === end.getFullYear();
		const s = `${start.getDate()} ${formatMonthShort(start)}`;
		const e = `${end.getDate()} ${formatMonthShort(end)}`;
		return sameMonth ? `${start.getDate()}-${end.getDate()} ${formatMonthShort(end)}` : `${s} - ${e}`;
	}

	function formatMonthTitle(d) {
		return d.toLocaleString("ru-RU", { month: "long", year: "numeric" });
	}

	function formatDow(d) {
		const names = ["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"];
		return names[(d.getDay() + 6) % 7];
	}

	function formatMonthShort(d) {
		return d.toLocaleString("ru-RU", { month: "short" }).replace(".", "");
	}

	function formatDateISO(d) {
		const x = new Date(d);
		return `${x.getFullYear()}-${pad2(x.getMonth() + 1)}-${pad2(x.getDate())}`;
	}

	function formatHm(d) {
		return `${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
	}

	function pad2(n) {
		return String(n).padStart(2, "0");
	}

	function roundToNext15(d) {
		const x = new Date(d);
		x.setSeconds(0, 0);
		const m = x.getMinutes();
		x.setMinutes(Math.ceil(m / 15) * 15);
		return x;
	}

	function toOffsetIso(date) {
		const d = new Date(date);
		const y = d.getFullYear();
		const m = pad2(d.getMonth() + 1);
		const day = pad2(d.getDate());
		const hh = pad2(d.getHours());
		const mm = pad2(d.getMinutes());
		const ss = pad2(d.getSeconds());
		const off = -d.getTimezoneOffset();
		const sign = off >= 0 ? "+" : "-";
		const abs = Math.abs(off);
		return `${y}-${m}-${day}T${hh}:${mm}:${ss}${sign}${pad2(Math.floor(abs / 60))}:${pad2(abs % 60)}`;
	}

	function toOffsetIsoFromInput(value) {
		const trimmed = (value || "").trim();
		if (!trimmed) return null;
		const parsed = new Date(trimmed);
		if (Number.isNaN(parsed.getTime())) return null;
		return toOffsetIso(parsed);
	}

	function toDatetimeLocalValue(value) {
		if (!value) return "";
		const parsed = new Date(value);
		if (Number.isNaN(parsed.getTime())) return "";
		return `${parsed.getFullYear()}-${pad2(parsed.getMonth() + 1)}-${pad2(parsed.getDate())}T${pad2(parsed.getHours())}:${pad2(parsed.getMinutes())}`;
	}

	function formatTimeRangeFromMinutes(startMin, endMin) {
		const sH = Math.floor(startMin / 60);
		const sM = startMin % 60;
		const eH = Math.floor(endMin / 60);
		const eM = endMin % 60;
		return `${pad2(sH)}:${pad2(sM)}-${pad2(eH)}:${pad2(eM)}`;
	}

	function escapeHtml(s) {
		return String(s)
			.replaceAll("&", "&amp;")
			.replaceAll("<", "&lt;")
			.replaceAll(">", "&gt;")
			.replaceAll('"', "&quot;")
			.replaceAll("'", "&#039;");
	}
})();
