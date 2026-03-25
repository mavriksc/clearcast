document.addEventListener("DOMContentLoaded", () => {
  console.log("radar: init");
  const dataEl = document.getElementById("radar-data");
  const image = document.getElementById("radar-image");
  if (!dataEl || !image) {
    console.log("radar: missing data element or image");
    return;
  }

  let data;
  try {
    data = JSON.parse(dataEl.textContent || "{}");
  } catch (err) {
    console.log("radar: failed to parse data", err);
    return;
  }

  const frames = Array.isArray(data.frames) ? data.frames : [];
  if (frames.length === 0) {
    console.log("radar: no frames");
    return;
  }
  console.log("radar: frames", frames.length);

  let index = 0;
  image.src = frames[0];
  if (frames.length === 1) {
    console.log("radar: single frame");
    return;
  }
  setInterval(() => {
    index = (index + 1) % frames.length;
    image.src = frames[index];
  }, 1000);
});
