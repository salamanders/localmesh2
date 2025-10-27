const deviceIdSpan = document.getElementById('device-id');
const peerCountSpan = document.getElementById('peer-count');
const peersList = document.getElementById('peers');
const foldersList = document.getElementById('folders');

async function updateStatus() {
    try {
        const statusString = Android.getStatus();
        const status = JSON.parse(statusString);
        console.info('Got status', JSON.stringify(status));

        deviceIdSpan.textContent = status.id || '...';

        // Update Peer List
        const peerMap = status.peers; // e.g., {"peer1":1, "peer2":1}
        const newPeerIds = Object.keys(peerMap);

        peerCountSpan.textContent = newPeerIds.length;
//        peersList.innerHTML = ''; // Clear existing list
//
//        newPeerIds.sort().forEach(id => {
//            const li = document.createElement('li');
//            // Display the peer ID and its distance
//            li.textContent = `${id} (distance: ${peerMap[id]})`;
//            li.dataset.peerId = id;
//            peersList.appendChild(li);
//        });


        // Update folder list (visualizations)
        const contentFolders = status.visualizations;
        foldersList.innerHTML = '';
        contentFolders.toSorted().forEach(folder => {
            const li = document.createElement('li');
            li.textContent = folder;
            li.addEventListener('click', () => {
                const renderLocally = document.getElementById('renderLocally').checked;
                if (renderLocally) {
                    if (folder == 'camera') {
                        Android.sendPeerDisplayCommand('slideshow');
                        window.location.href = 'camera/index.html';
                    } else {
                        window.location.href = folder + '/index.html';
                    }
                } else {
                    Android.sendPeerDisplayCommand(folder);
                }
            });
            foldersList.appendChild(li);
        });
    } catch (e) {
        console.error('Failed to fetch status:', e);
    }
}

// Initial fetch and then poll
updateStatus();
setInterval(updateStatus, 10 * 1000);
console.log('LOCALMESH_SCRIPT_SUCCESS:root');
