(() => {
	const ROW_HEIGHT_PX = 48;
	const DAY_MINUTES = 24 * 60;

	const config = (window.__APP_CONFIG__ || {});
	const apiBaseUrl = (config.apiBaseUrl || "").replace(/\/+$/, "");

	const tg = window.Telegram && window.Telegram.WebApp ? window.Telegram.WebApp : null;
	if (tg && typeof tg.ready === "function") {
		tg.ready();
	}

	const el = {
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
		meetings: [],
		editingId: null,
		nowTimer: null
	};

	init();

	function init() {
		hydrateUser();
		bindUi();
		render();
		scheduleNowLine();
	}

	function hydrateUser() {
		try {
			if (tg && tg.initDataUnsafe && tg.initDataUnsafe.user) {
				const u = tg.initDataUnsafe.user;
				el.userName.textContent = (u.first_name || u.username || "Вы");
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
		el.prevBtn.addEventListener("click", () => {
			state.weekStart = addDays(state.weekStart, -7);
			render();
		});
		el.nextBtn.addEventListener("click", () => {
			state.weekStart = addDays(state.weekStart, 7);
			render();
		});
		el.todayBtn.addEventListener("click", () => {
			state.weekStart = startOfWeek(new Date());
			render();
		});
		el.reloadBtn.addEventListener("click", () => {
			loadMeetings().then(renderWeekEvents);
		});
		el.newBtn.addEventListener("click", () => {
			openCreateModal();
		});

		el.pills.forEach((pill) => {
			pill.addEventListener("click", () => {
				const view = pill.getAttribute("data-view");
				if (!view) return;
				state.view = view;
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

	function render() {
		if (state.view !== "week") {
			state.view = "week";
			el.pills.forEach((p) => p.classList.toggle("active", p.getAttribute("data-view") === "week"));
		}
		renderWeekGrid();
		loadMeetings().then(renderWeekEvents);
	}

	function renderWeekGrid() {
		const start = new Date(state.weekStart);
		const end = addDays(start, 6);
		el.rangeLabel.textContent = formatRange(start, end);

		const today = startOfDay(new Date());
		const todayIndex = sameDayRangeIndex(startOfDay(start), today);

		// Head
		el.gridHead.innerHTML = "";
		const headBlank = document.createElement("div");
		headBlank.className = "head-cell";
		el.gridHead.appendChild(headBlank);
		for (let d = 0; d < 7; d++) {
			const day = addDays(start, d);
			const cell = document.createElement("div");
			cell.className = "head-cell";
			const inner = document.createElement("div");
			inner.className = "head-day";
			if (sameDay(day, today)) inner.classList.add("today");
			inner.innerHTML = `
				<div class="dow">${formatDow(day)}</div>
				<div class="date">${formatDayMonth(day)}</div>
			`;
			cell.appendChild(inner);
			el.gridHead.appendChild(cell);
		}

		// Body background cells
		el.gridBody.innerHTML = "";
		for (let hour = 0; hour < 24; hour++) {
			// time label
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

		// Day overlay layers (for events)
		for (let d = 0; d < 7; d++) {
			const layer = document.createElement("div");
			layer.className = "day-layer";
			layer.dataset.dayIndex = String(d);
			layer.style.gridRow = `1 / span 24`;
			layer.style.gridColumn = String(2 + d);
			el.gridBody.appendChild(layer);
		}

		// Now line layer
		const nowLayer = document.createElement("div");
		nowLayer.className = "now-layer";
		nowLayer.style.gridRow = `1 / span 24`;
		nowLayer.style.gridColumn = `2 / span 7`;
		nowLayer.id = "nowLayer";
		el.gridBody.appendChild(nowLayer);

		updateNowLine();
		scrollToNowIfVisible();
	}

	async function loadMeetings() {
		if (!apiBaseUrl) {
			el.syncStatus.textContent = "Не настроен API (config.js)";
			state.meetings = [];
			return [];
		}

		const from = formatDateISO(state.weekStart);
		const to = formatDateISO(addDays(state.weekStart, 6));
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
			el.syncStatus.textContent = "Загрузка…";
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
		const layers = Array.from(el.gridBody.querySelectorAll(".day-layer"));
		layers.forEach((l) => (l.innerHTML = ""));

		const start = startOfDay(state.weekStart);
		const dayBuckets = new Array(7).fill(0).map(() => []);

		for (const m of state.meetings) {
			const startDt = new Date(m.startsAt);
			const endDt = new Date(m.endsAt);
			for (let d = 0; d < 7; d++) {
				const day = addDays(start, d);
				const dayStart = day;
				const dayEnd = addMinutes(addDays(dayStart, 1), 0);
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
			renderDayEvents(layer, events);
		});

		updateNowLine();
	}

	function renderDayEvents(layer, events) {
		const sorted = events
			.slice()
			.sort((a, b) => (a.startMin - b.startMin) || (a.endMin - b.endMin));

		const active = [];
		const assigned = [];
		let maxLanes = 1;

		for (const ev of sorted) {
			// cleanup
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
			const top = (ev.startMin / 60) * ROW_HEIGHT_PX;
			const height = Math.max(22, ((Math.max(ev.endMin, ev.startMin + 10) - ev.startMin) / 60) * ROW_HEIGHT_PX);
			const widthPercent = 100 / maxLanes;
			const leftPercent = ev.lane * widthPercent;

			const node = document.createElement("div");
			node.className = "event";
			node.style.top = `${top + 2}px`;
			node.style.height = `${height - 4}px`;
			node.style.left = `calc(${leftPercent}% + 6px)`;
			node.style.width = `calc(${widthPercent}% - 12px)`;
			node.innerHTML = `
				<div class="event-title">${escapeHtml(ev.title)}</div>
				<div class="event-meta">${formatTimeRangeFromMinutes(ev.startMin, ev.endMin)}${ev.location ? " • " + escapeHtml(ev.location) : ""}</div>
			`;
			node.addEventListener("click", () => openEditModal(ev));
			layer.appendChild(node);
		}
	}

	function scheduleNowLine() {
		if (state.nowTimer) clearInterval(state.nowTimer);
		state.nowTimer = setInterval(updateNowLine, 60 * 1000);
		updateNowLine();
	}

	function updateNowLine() {
		const nowLayer = document.getElementById("nowLayer");
		if (!nowLayer) return;
		nowLayer.innerHTML = "";

		const now = new Date();
		const weekStart = startOfDay(state.weekStart);
		const weekEnd = addDays(weekStart, 7);
		if (now < weekStart || now >= weekEnd) return;

		const dayIndex = Math.floor((startOfDay(now).getTime() - weekStart.getTime()) / (24 * 60 * 60 * 1000));
		if (dayIndex < 0 || dayIndex > 6) return;

		const minutes = now.getHours() * 60 + now.getMinutes();
		const top = (minutes / 60) * ROW_HEIGHT_PX;

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
		const now = new Date();
		const weekStart = startOfDay(state.weekStart);
		const weekEnd = addDays(weekStart, 7);
		if (now < weekStart || now >= weekEnd) return;

		const minutes = now.getHours() * 60 + now.getMinutes();
		const top = (minutes / 60) * ROW_HEIGHT_PX;
		// keep some top padding like in my.itmo (time not glued to the top)
		el.gridBody.scrollTop = Math.max(0, top - 120);
	}

	function sameDayRangeIndex(weekStartDay, day) {
		const a = startOfDay(weekStartDay).getTime();
		const b = startOfDay(day).getTime();
		const diffDays = Math.floor((b - a) / (24 * 60 * 60 * 1000));
		return diffDays >= 0 && diffDays < 7 ? diffDays : -1;
	}

	function openCreateModal() {
		state.editingId = null;
		el.modalTitle.textContent = "Новое событие";
		el.deleteBtn.style.visibility = "hidden";

		const start = roundToNext15(new Date());
		const end = addMinutes(start, 60);

		el.eventTitle.value = "";
		el.eventStart.value = toOffsetIso(start);
		el.eventEnd.value = toOffsetIso(end);
		el.eventLocation.value = "";
		el.eventLink.value = "";

		openModal();
	}

	function openEditModal(ev) {
		state.editingId = ev.id;
		el.modalTitle.textContent = "Событие";
		el.deleteBtn.style.visibility = "visible";

		el.eventTitle.value = ev.title || "";
		el.eventStart.value = ev.startsAt || "";
		el.eventEnd.value = ev.endsAt || "";
		el.eventLocation.value = ev.location || "";
		el.eventLink.value = ev.externalLink || "";

		openModal();
	}

	function openModal() {
		el.modal.classList.remove("hidden");
		setTimeout(() => {
			el.eventTitle && el.eventTitle.focus();
		}, 0);
	}

	function closeModal() {
		el.modal.classList.add("hidden");
	}

	async function saveMeetingFromModal() {
		if (!apiBaseUrl) return;

		const payload = {
			title: (el.eventTitle.value || "").trim(),
			startsAt: (el.eventStart.value || "").trim() || null,
			endsAt: (el.eventEnd.value || "").trim() || null,
			location: (el.eventLocation.value || "").trim() || null,
			externalLink: (el.eventLink.value || "").trim() || null
		};

		if (!payload.title || !payload.startsAt || !payload.endsAt) {
			alert("Заполните: Название, Начало (ISO), Конец (ISO)");
			return;
		}

		const telegramId = getTelegramIdFromUrl();
		const initData = tg && typeof tg.initData === "string" ? tg.initData : "";

		const headers = { "Content-Type": "application/json" };
		if (initData) headers["X-Telegram-Init-Data"] = initData;

		try {
			let url;
			let method;
			if (state.editingId) {
				url = new URL(apiBaseUrl + `/api/miniapp/meetings/${state.editingId}`);
				method = "PATCH";
			} else {
				url = new URL(apiBaseUrl + "/api/miniapp/meetings");
				method = "POST";
			}
			if (!initData && telegramId) {
				url.searchParams.set("telegramId", String(telegramId));
			}

			const res = await fetch(url.toString(), {
				method,
				headers,
				body: JSON.stringify(payload)
			});
			if (!res.ok) {
				const txt = await res.text().catch(() => "");
				alert(`Ошибка сохранения: ${res.status}\n${txt}`);
				return;
			}
			closeModal();
			await loadMeetings();
			renderWeekEvents();
		} catch (e) {
			console.warn(e);
			alert("Ошибка сети при сохранении");
		}
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
			await loadMeetings();
			renderWeekEvents();
		} catch (e) {
			console.warn(e);
			alert("Ошибка сети при удалении");
		}
	}

	// helpers
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
		// Monday as first day
		const day = (x.getDay() + 6) % 7; // 0..6 where 0 is Monday
		return addDays(x, -day);
	}

	function addDays(d, days) {
		const x = new Date(d);
		x.setDate(x.getDate() + days);
		return x;
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
		return Math.round((date.getTime() - dayStart.getTime()) / (60 * 1000));
	}

	function sameDay(a, b) {
		return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
	}

	function formatRange(start, end) {
		const sameMonth = start.getMonth() === end.getMonth() && start.getFullYear() === end.getFullYear();
		const s = `${start.getDate()} ${formatMonthShort(start)}`;
		const e = `${end.getDate()} ${formatMonthShort(end)}`;
		return sameMonth ? `${start.getDate()}–${end.getDate()} ${formatMonthShort(end)}` : `${s} – ${e}`;
	}

	function formatDow(d) {
		const names = ["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"];
		const i = (d.getDay() + 6) % 7;
		return names[i];
	}

	function formatMonthShort(d) {
		return d.toLocaleString("ru-RU", { month: "short" }).replace(".", "");
	}

	function formatDayMonth(d) {
		const m = d.toLocaleString("ru-RU", { month: "short" }).replace(".", "");
		return `${d.getDate()} ${m}`;
	}

	function formatDateISO(d) {
		const x = new Date(d);
		const y = x.getFullYear();
		const m = pad2(x.getMonth() + 1);
		const day = pad2(x.getDate());
		return `${y}-${m}-${day}`;
	}

	function pad2(n) {
		return String(n).padStart(2, "0");
	}

	function roundToNext15(d) {
		const x = new Date(d);
		x.setSeconds(0, 0);
		const m = x.getMinutes();
		const next = Math.ceil(m / 15) * 15;
		x.setMinutes(next);
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
		const oh = pad2(Math.floor(abs / 60));
		const om = pad2(abs % 60);
		return `${y}-${m}-${day}T${hh}:${mm}:${ss}${sign}${oh}:${om}`;
	}

	function formatTimeRangeFromMinutes(startMin, endMin) {
		const sH = Math.floor(startMin / 60);
		const sM = startMin % 60;
		const eH = Math.floor(endMin / 60);
		const eM = endMin % 60;
		return `${pad2(sH)}:${pad2(sM)}–${pad2(eH)}:${pad2(eM)}`;
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
