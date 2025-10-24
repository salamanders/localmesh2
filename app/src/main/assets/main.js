const deviceIdSpan = document.getElementById('device-id');
const statusTextSpan = document.getElementById('status-text');
const peerCountSpan = document.getElementById('peer-count');
const peersList = document.getElementById('peers');
const foldersList = document.getElementById('folders');

async function updateStatus() {
    try {
        const statusString = Android.getStatus();
        const status = JSON.parse(statusString);
        console.info('Got status', JSON.stringify(status));

        deviceIdSpan.textContent = status.id || '...';
        statusTextSpan.textContent = 'Online'; // Hardcoded for now

        // Update Peer List
        peerCountSpan.textContent = status.peers.length;
        const newPeerIds = status.peers.map(p => p.id);
        peersList.innerHTML = '';
        newPeerIds.toSorted().forEach(id => {
            const li = document.createElement('li');
            li.textContent = id;
            li.dataset.peerId = id;
            peersList.appendChild(li);
        });

        // Update folder list (visualizations)
        const contentFolders = status.visualizations;
        foldersList.innerHTML = '';
        contentFolders.toSorted().forEach(folder => {
            const li = document.createElement('li');
            li.textContent = folder;
            if(folder == 'camera') {
                li.addEventListener('click', () => {
                    Android.sendPeerDisplayCommand('slideshow');
                    window.location.href = '/camera/index.html';
                });
            } else {
                li.addEventListener('click', () => Android.sendPeerDisplayCommand(folder));
            }
            foldersList.appendChild(li);
        });
    } catch (e) {
        statusTextSpan.textContent = 'Error';
        console.error('Failed to fetch status:', e);
    }
}

// Initial fetch and then poll every 3 seconds
updateStatus();
setInterval(updateStatus, 5000);
console.log('LOCALMESH_SCRIPT_SUCCESS:root');
