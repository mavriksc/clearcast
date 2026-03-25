document.addEventListener("DOMContentLoaded", () => {
  const dataEl = document.getElementById("radar-data");
  const image = document.getElementById("radar-image");
  if (!dataEl || !image) return;

  let data;
  try {
    data = JSON.parse(dataEl.textContent || "{}");
  } catch {
    return;
  }

  const frames = Array.isArray(data.frames) ? data.frames : [];
  if (frames.length === 0) return;

  let index = 0;
  image.src = frames[0];
  if (frames.length === 1) return;
  setInterval(() => {
    index = (index + 1) % frames.length;
    image.src = frames[index];
  }, 1000);
});
