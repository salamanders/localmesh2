const deviceIdSpan = document.getElementById('device-id');
const deviceRoleSpan = document.getElementById('device-role');
const peerCountSpan = document.getElementById('peer-count');
const peersList = document.getElementById('peers');
const foldersList = document.getElementById('folders');

async function updateStatus() {
    try {
        const statusString = Android.getStatus();
        const status = JSON.parse(statusString);
        console.info('Got status', JSON.stringify(status));

        deviceIdSpan.textContent = status.id || '...';
        if (deviceRoleSpan) {
            deviceRoleSpan.textContent = status.role || '...';
        }

        peerCountSpan.textContent = status.peers.length;

        if (foldersList) {
            // Update folder list (visualizations)
            const contentFolders = status.visualizations;
            foldersList.innerHTML = '';
            contentFolders.toSorted().forEach(folder => {
                const li = document.createElement('li');
                li.textContent = folder;
                li.addEventListener('click', () => {
                    if (status.role === 'COMMANDER') {
                        Android.sendPeerDisplayCommand(folder);
                    } else {
                        // This is "Display Locally"
                        if (folder === 'camera') {
                            Android.sendPeerDisplayCommand('slideshow');
                            window.location.href = 'camera/index.html';
                        } else {
                            window.location.href = folder + '/index.html';
                        }
                    }
                });
                foldersList.appendChild(li);
            });
        }
    } catch (e) {
        console.error('Failed to fetch status:', e);
    }
}

// Initial fetch and then poll
updateStatus();
setInterval(updateStatus, 30 * 1000);
console.log('LOCALMESH_SCRIPT_SUCCESS:root');
