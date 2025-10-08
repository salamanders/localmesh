const deviceIdSpan = document.getElementById('device-id');
const statusTextSpan = document.getElementById('status-text');
const peerCountSpan = document.getElementById('peer-count');
const peersList = document.getElementById('peers');
const foldersList = document.getElementById('folders');
const noPeersLi = document.getElementById('no-peers');

// These files are part of the base UI and not actual content folders.
const FOLDER_BLACKLIST = ['favicon.ico', 'index.html'];

async function updateStatus() {
    try {
        const response = await fetch('/status');
        if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);
        const data = await response.json();

        deviceIdSpan.textContent = data.id || '...';
        statusTextSpan.textContent = 'Running';
        peerCountSpan.textContent = data.peerCount;

        if (data.peerCount > 0) {
            noPeersLi.style.display = 'none';
            const currentPeerIds = Array.from(peersList.children).map(li => li.dataset.peerId);
            const newPeerIds = data.peerIds;

            // Remove peers that are no longer connected
            for (const id of currentPeerIds) {
                if (!newPeerIds.includes(id)) {
                    peersList.querySelector(`[data-peer-id="${id}"]`).remove();
                }
            }
            // Add new peers
            for (const id of newPeerIds) {
                if (!currentPeerIds.includes(id)) {
                    const li = document.createElement('li');
                    li.textContent = id;
                    li.dataset.peerId = id;
                    peersList.appendChild(li);
                }
            }
        } else {
            peersList.innerHTML = '';
            peersList.appendChild(noPeersLi);
            noPeersLi.style.display = 'block';
        }
    } catch (e) {
        statusTextSpan.textContent = 'Error';
        console.error('Failed to fetch status:', e);
    }
}

async function fetchFolders() {
    try {
        const response = await fetch('/folders');
         if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);
        const data = await response.json();
        const contentFolders = data.filter(item => !FOLDER_BLACKLIST.includes(item));

        if (contentFolders.length > 0) {
            foldersList.innerHTML = contentFolders.map(folder => `<li onclick="displayFolder('${folder}')">${folder}</li>`).join('');
        } else {
            foldersList.innerHTML = '<li>No content folders found in assets.</li>';
        }
    } catch (e) {
        foldersList.innerHTML = '<li>Error loading folders.</li>';
        console.error('Failed to fetch folders:', e);
    }
}

function displayFolder(folderName) {
    // We don't care about the response, just that the request is sent.
    fetch(`/display?path=${folderName}`).catch(e => console.error('Failed to send display command:', e));
}

// Initial fetch and then poll every 3 seconds
updateStatus();
fetchFolders();
setInterval(updateStatus, 3000);