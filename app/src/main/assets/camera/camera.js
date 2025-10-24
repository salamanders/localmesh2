const cameraInput = document.getElementById('camera-input');
const uploadButton = document.getElementById('upload-button');

let deviceId = null;

// Fetch the device ID to create unique filenames
async function getDeviceId() {
    const response = await fetch('/status');
    if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
    }
    const data = await response.json();
    if (!data.id) {
        throw new Error(`Status error! data: ${data}`);
    }
    deviceId = data.id;
}

getDeviceId();

uploadButton.addEventListener('click', async () => {
    const file = cameraInput.files[0];
    if (!file) {
        alert('Please select a file first.');
        return;
    }

    if (!deviceId) {
        await getDeviceId(); // Ensure we have a device ID
    }

    try {
        const resizedBlob = await resizeImage(file, 800);
        const timestamp = Math.floor((new Date().getTime() - new Date('2025-10-01').getTime()) / 1000);
        const uniqueFileName = `photos/camera_${deviceId}_${timestamp}.jpg`;

        const formData = new FormData();
        formData.append('file', resizedBlob, uniqueFileName);

        console.log(`Uploading ${uniqueFileName}...`);
        const response = await fetch('/send-file', {
            method: 'POST',
            body: formData,
        });

        if (!response.ok) {
            throw new Error(`Upload failed with status: ${response.status}`);
        }

        console.log('Upload successful. Notifying peers...');
        // Notify all other peers to switch to slideshow and add the new image
        await fetch(`/display?path=slideshow`);
        console.log('Notification sent.');
        alert('Image uploaded and sent to slideshow!');

    } catch (error) {
        console.error('Error during upload:', error);
        alert(`Error: ${error.message}`);
    }
});


function resizeImage(file, maxDimension) {
    return new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = (event) => {
            const img = new Image();
            img.onload = () => {
                const canvas = document.createElement('canvas');
                let { width, height } = img;

                if (width > height) {
                    if (width > maxDimension) {
                        height = Math.round(height * (maxDimension / width));
                        width = maxDimension;
                    }
                } else {
                    if (height > maxDimension) {
                        width = Math.round(width * (maxDimension / height));
                        height = maxDimension;
                    }
                }

                canvas.width = width;
                canvas.height = height;
                const ctx = canvas.getContext('2d');
                ctx.drawImage(img, 0, 0, width, height);

                canvas.toBlob((blob) => {
                    if (blob) {
                        resolve(blob);
                    } else {
                        reject(new Error('Canvas to Blob conversion failed'));
                    }
                }, 'image/jpeg', 0.9); // 90% quality
            };
            img.onerror = reject;
            img.src = event.target.result;
        };
        reader.onerror = reject;
        reader.readAsDataURL(file);
    });
}
