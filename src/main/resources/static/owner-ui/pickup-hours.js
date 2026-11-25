"use strict";

function bindPickupHours(container, onChange) {
  const fullDay = container.querySelector('[value="full"]');
  const setHours = container.querySelector('[value="range"]');
  const range = container.querySelector('[data-hours-range]');
  const start = container.querySelector('[data-hours-start]');
  const end = container.querySelector('[data-hours-end]');
  let saved;

  function sync() {
    range.hidden = fullDay.checked;
    start.disabled = end.disabled = fullDay.checked;
    onChange?.();
  }

  function read() {
    if (fullDay.checked) return { allDay: true };
    if (!start.value || !end.value || start.value > end.value) {
      throw new Error("Choose an end time at or after the start time.");
    }
    return { allDay: false, opensAt: start.value, closesAt: end.value };
  }

  container.addEventListener("input", sync);
  return {
    load(hours) {
      if (!hours) {
        saved = undefined;
        container.hidden = true;
        container.disabled = true;
        return;
      }
      fullDay.checked = hours.allDay;
      setHours.checked = !hours.allDay;
      start.value = hours.opensAt || "09:00";
      end.value = hours.closesAt || "21:00";
      saved = JSON.stringify(read());
      container.hidden = false;
      container.disabled = false;
      sync();
    },
    read,
    changed() {
      if (!saved) return false;
      try { return JSON.stringify(read()) !== saved; }
      catch { return true; }
    }
  };
}
