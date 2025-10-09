import * as THREE from './three.module.js';
import * as TWEEN from './tween.esm.js';

let camera, scene, renderer, meshEye, meshLidRotated;

const getRandomRange = (min, max) => {
    return Math.random() * (max - min) + min;
};

const lookSomewhere = () => {
    // y = side side, +x = down
    new TWEEN.Tween(meshEye.rotation)
        .to({
            x: THREE.MathUtils.degToRad(getRandomRange(-15, 15)),
            y: THREE.MathUtils.degToRad(getRandomRange(-30, 30))
        }, getRandomRange(10, 200))
        .start();

    setTimeout(() => {
        lookSomewhere();
    }, getRandomRange(200, 3000));
};

const onWindowResize = () => {
    camera.aspect = window.innerWidth / window.innerHeight;
    console.log(`onWindowResize: camera.aspect=${camera.aspect}`);
    camera.updateProjectionMatrix();
    renderer.setSize(window.innerWidth, window.innerHeight);
};
window.addEventListener('resize', onWindowResize, false);

const animate = () => {
    requestAnimationFrame(animate);
    TWEEN.update();
    renderer.render(scene, camera);
};

const eyeTextures = [
    'img/eye1.jpg',
    'img/eye2.jpg',
    'img/eye3.jpg',
    'img/eye4.jpg',
    'img/highres.jpg',
    'img/scary.jpg'
];

const init = () => {
    renderer = new THREE.WebGLRenderer();
    renderer.setSize(window.innerWidth, window.innerHeight);
    document.body.appendChild(renderer.domElement);

    scene = new THREE.Scene();

    camera = new THREE.PerspectiveCamera(40, window.innerWidth / window.innerHeight, 1, 1000);
    camera.position.set(0, 0, 80);
    scene.add(camera);

    scene.add(new THREE.AmbientLight(0xffffff, 0.2));

    const light = new THREE.PointLight(0xffffff, 1);
    camera.add(light);

    const eyeTextureUrl = eyeTextures[Math.floor(Math.random() * eyeTextures.length)];
    console.log(eyeTextureUrl);

    const texture = new THREE.TextureLoader().load(eyeTextureUrl);

    const material = new THREE.MeshPhongMaterial({
        color: 0xffffff,
        specular: 0x050505,
        shininess: 50,
        map: texture
    });

    const geometry = new THREE.SphereGeometry(30, 24, 24);

    // The old method of modifying faceVertexUvs is deprecated.
    // The modern approach for a MatCap is to use MeshMatcapMaterial,
    // but to preserve the original Phong shading, we can manipulate the UV attribute.
    const uvs = geometry.attributes.uv.array;
    const normals = geometry.attributes.normal.array;
    for (let i = 0; i < uvs.length; i += 2) {
        const normalIndex = (i / 2) * 3;
        uvs[i] = normals[normalIndex] * 0.5 + 0.5;
        uvs[i + 1] = normals[normalIndex + 1] * 0.5 + 0.5;
    }
    geometry.attributes.uv.needsUpdate = true;


    meshEye = new THREE.Mesh(geometry, material);
    scene.add(meshEye);

    // Eyelid
    const geometryLid = new THREE.SphereGeometry(30.6, 24, 24, (Math.PI * 2) * 0.25, (Math.PI * 2) * 0.80);
    const meshLid = new THREE.Mesh(geometryLid, new THREE.MeshPhongMaterial({
        color: 0x111111,
        specular: 0x050505,
        shininess: 20
    }));
    // x side side, y up down
    meshLid.rotation.z = Math.PI / 2;
    meshLid.rotation.x = -Math.PI / 4;

    meshLidRotated = new THREE.Object3D();
    meshLidRotated.add(meshLid);

    scene.add(meshLidRotated);
};

init();
onWindowResize();
lookSomewhere();
console.log(meshEye.rotation.x, meshEye.rotation.y, meshEye.rotation.z);
animate();
