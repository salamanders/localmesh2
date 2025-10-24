const slideshowImage = document.getElementById('slideshow-image');
const SLIDESHOW_INTERVAL = 5000; // 5 seconds to change image
const IMAGE_FETCH_INTERVAL = 15000; // 15 seconds to check for new images

let imageList = [];

async function fetchImages() {
    try {
        const response = await fetch('/list?path=photos&type=files');
        if (!response.ok) {
            console.error('Failed to fetch image list:', response.status);
            return;
        }
        imageList = await response.json();
        console.log('Updated image list:', imageList);
    } catch (error) {
        console.error('Error fetching image list:', error);
    }
}

function showRandomImage() {
    if (imageList.length === 0) {
        slideshowImage.alt = "Waiting for images...";
        slideshowImage.src = "";
        return;
    }

    const randomIndex = Math.floor(Math.random() * imageList.length);
    const imageUrl = `/photos/${imageList[randomIndex]}`;
    slideshowImage.src = imageUrl;
    slideshowImage.alt = imageList[randomIndex];
}

async function initSlideshow() {
    await fetchImages();
    showRandomImage();
    setInterval(showRandomImage, SLIDESHOW_INTERVAL);
    setInterval(fetchImages, IMAGE_FETCH_INTERVAL);
}

initSlideshow();