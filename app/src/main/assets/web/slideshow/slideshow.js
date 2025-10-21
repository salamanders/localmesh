const slideshowImage = document.getElementById('slideshow-image');
const IMAGE_LIST_KEY = 'slideshowImageList';
const SLIDESHOW_INTERVAL = 5000; // 5 seconds
const PEER_ID_KEY = 'slideshowPeerId';

function getPeerId() {
    let peerId = sessionStorage.getItem(PEER_ID_KEY);
    if (!peerId) {
        peerId = `peer_${Math.random().toString(36).substr(2, 9)}`;
        sessionStorage.setItem(PEER_ID_KEY, peerId);
    }
    return peerId;
}

function getImages() {
    const imagesJson = localStorage.getItem(IMAGE_LIST_KEY);
    return imagesJson ? JSON.parse(imagesJson) : [];
}

function addImage(newImage) {
    const images = getImages();
    if (!images.includes(newImage)) {
        images.push(newImage);
        localStorage.setItem(IMAGE_LIST_KEY, JSON.stringify(images));
    }
}

function setImages(imageList) {
    localStorage.setItem(IMAGE_LIST_KEY, JSON.stringify(imageList));
}


function showRandomImage() {
    const images = getImages();
    if (images.length === 0) {
        slideshowImage.alt = "Waiting for images...";
        slideshowImage.src = "";
        return;
    }

    const randomIndex = Math.floor(Math.random() * images.length);
    const imageUrl = `/${images[randomIndex]}`;
    slideshowImage.src = imageUrl;
    slideshowImage.alt = images[randomIndex];
}

async function handleUrlParameters() {
    const urlParams = new URLSearchParams(window.location.search);
    const newImage = urlParams.get('newImage');
    const requestImageList = urlParams.get('requestImageList');
    const imageList = urlParams.get('imageList');
    const forPeer = urlParams.get('forPeer');
    const myPeerId = getPeerId();

    if (newImage) {
        addImage(newImage);
        window.location.href = window.location.pathname;
        return true;
    }

    if (requestImageList && requestImageList !== myPeerId) {
        // Another peer is asking for the image list.
        // Respond with a 50% chance to avoid a broadcast storm.
        if (Math.random() > 0.5) {
            const images = getImages();
            if (images.length > 0) {
                const imageListParam = encodeURIComponent(JSON.stringify(images));
                await fetch(`/display?path=slideshow&imageList=${imageListParam}&forPeer=${requestImageList}`);
            }
        }
    }

    if (imageList && forPeer === myPeerId) {
        // We received an image list from a peer.
        const decodedList = JSON.parse(decodeURIComponent(imageList));
        setImages(decodedList);
        window.location.href = window.location.pathname; // Reload to clean url
        return true;
    }
    return false;
}

async function requestImageListIfNeeded() {
    if (getImages().length === 0) {
        const myPeerId = getPeerId();
        await fetch(`/display?path=slideshow&requestImageList=${myPeerId}`);
    }
}


// --- Main Logic ---
if (!handleUrlParameters()) {
    requestImageListIfNeeded();
    showRandomImage();
    setInterval(showRandomImage, SLIDESHOW_INTERVAL);
}
