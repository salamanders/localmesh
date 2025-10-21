const deviceIdSpan = document.getElementById('device-id');
const statusTextSpan = document.getElementById('status-text');
const peerCountSpan = document.getElementById('peer-count');
const peersList = document.getElementById('peers');
const foldersList = document.getElementById('folders');
const noPeersLi = document.getElementById('no-peers');

// These files are part of the base UI and not actual content folders.
const FOLDER_BLACKLIST = ['favicon.ico', 'index.html', 'main.js'];

/**
 * A centralized fetch wrapper that adds a spoofed sourceNodeId if the
 * input box is filled out.
 * @param {string} path - The request path (e.g., '/status').
 * @param {RequestInit} options - The options for the fetch call.
 * @returns {Promise<Response>}
 */
function sendRequest(path, options = {}) {
    const sourceNodeIdInput = document.getElementById('sourceNodeIdInput');
    let finalPath = path;

    if (sourceNodeIdInput && sourceNodeIdInput.value) {
        const separator = finalPath.includes('?') ? '&' : '?';
        finalPath += `${separator}sourceNodeId=${encodeURIComponent(sourceNodeIdInput.value)}`;
    }
    console.log(`Sending request to: ${finalPath}`);
    return fetch(finalPath, options);
}


async function updateStatus() {
    try {
        const response = await sendRequest('/status');
        if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);
        const data = await response.json();

        deviceIdSpan.textContent = data.id || '...';
        statusTextSpan.textContent = data.status || 'Unknown';
        peerCountSpan.textContent = data.peerCount;

        if (data.peerCount > 0) {
            noPeersLi.style.display = 'none';
            const currentPeerIds = Array.from(peersList.children).map(li => li.dataset.peerId);
            const newPeerIds = data.peerIds;

            // Remove peers that are no longer connected
            for (const id of currentPeerIds) {
                if (!newPeerIds.includes(id)) {
                    const peerElement = peersList.querySelector(`[data-peer-id="${id}"]`);
                    if (peerElement) {
                        peerElement.remove();
                    }
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
        const response = await sendRequest('/folders');
        if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);
        const data = await response.json();
        const contentFolders = data.filter(item => !FOLDER_BLACKLIST.includes(item));

        foldersList.innerHTML = ''; // Clear existing

        // Add the special "Camera" option first
        const cameraLi = document.createElement('li');
        cameraLi.textContent = 'Camera';
        cameraLi.style.fontWeight = 'bold'; // Make it stand out
        cameraLi.addEventListener('click', () => {
            console.log('Camera option selected.');
            // First, navigate the local browser to the camera page
            window.location.href = '/camera/index.html';
            // Then, send the display command to peers to open the slideshow
            displayFolder('slideshow');
        });
        foldersList.appendChild(cameraLi);


        if (contentFolders.length > 0) {
            contentFolders.forEach(folder => {
                const li = document.createElement('li');
                li.textContent = folder;
                li.addEventListener('click', () => displayFolder(folder));
                foldersList.appendChild(li);
            });
        } else {
            // If there are no other folders, we don't need a message saying so,
            // because the Camera option is always there.
        }
    } catch (e) {
        foldersList.innerHTML = '<li>Error loading folders.</li>';
        console.error('Failed to fetch folders:', e);
    }
}

function displayFolder(folderName) {
    console.log(`displayFolder called with: ${folderName}`);
    // We don't care about the response, just that the request is sent.
    sendRequest(`/display?path=${folderName}`, {method: 'GET'})
        .catch(e => console.error('Failed to send display command:', e));
}

// Initial fetch and then poll every 3 seconds
updateStatus();
fetchFolders();
setInterval(updateStatus, 3000);
console.log('LOCALMESH_SCRIPT_SUCCESS:root');
