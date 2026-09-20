const ALLOWED_ACTIONS = new Set([
  // Climate
  "climate.on",
  "climate.off",
  "climate.auto_on",
  "climate.auto_off",
  "climate.recirculation_inner",
  "climate.recirculation_outer",
  "climate.rear_defrost_on",
  "climate.rear_defrost_off",
  "climate.front_defrost_on",
  "climate.front_defrost_off",
  "climate.flow_only_on",
  "climate.flow_only_off",
  "climate.temperature",
  "climate.fan_level",
  "climate.airflow_face",
  "climate.airflow_face_feet",
  "climate.airflow_feet",
  "climate.airflow_feet_windshield",
  "climate.airflow_windshield",
  "climate.airflow_face_feet_windshield",
  "climate.airflow_face_windshield",

  // Seats
  "seat.driver.heat",
  "seat.passenger.heat",
  "seat.driver.vent",
  "seat.passenger.vent",

  // Lights
  "light.interior_on",
  "light.interior_off",
  "light.ambient_on",
  "light.ambient_off",
  "light.drl_on",
  "light.drl_off",
  "light.hazard_on",
  "light.hazard_off",

  // Windows
  "window.driver.open",
  "window.driver.close",
  "window.driver.vent",
  "window.driver.position",
  "window.passenger.open",
  "window.passenger.close",
  "window.passenger.vent",
  "window.passenger.position",
  "window.rear_left.open",
  "window.rear_left.close",
  "window.rear_left.vent",
  "window.rear_left.position",
  "window.rear_right.open",
  "window.rear_right.close",
  "window.rear_right.vent",
  "window.rear_right.position",
  "window.all.open",
  "window.all.close",
  "window.all.half",
  "window.all.vent",

  // Locks / trunks / roof
  "doors.lock",
  "doors.unlock",
  "trunk.rear.open",
  "trunk.rear.close",
  "trunk.front.open",
  "trunk.front.close",
  "sunroof.open",
  "sunroof.close",
  "sunroof.tilt",
  "sunroof.position",
  "sunroof.vent",
  "sunroof.comfort",
  "sunroof.stop",
  "sunshade.open",
  "sunshade.close",

  // Fridge - bridge/API-ready even if no Yandex card is exposed yet
  "fridge.cool",
  "fridge.heat",
  "fridge.off",
  "fridge.cool_temperature",
  "fridge.heat_temperature",

  // Apps
  "app.navigation.open",
  "app.waze.open",
  "app.yandex_navi.open",
  "app.yandex_maps.open",
  "app.music.open",
  "app.youtube.open",
  "app.browser.open",
  "app.car_settings.open",
  "app.android_settings.open",
  "app.camera.open",
  "app.dashcam.open",
  "app.files.open",
  "app.drive_modes.open",
  "app.sentry.open",
  "app.abrp.open",
  "app.media_center.open",
  "app.phone.open",
  "app.radio.open",
  "app.bydmate.open",
  "app.tiktok.open",

  // Navigation / projection
  "navigation.cluster_on",
  "navigation.cluster_off",

  // Media
  "media.play",
  "media.pause",
  "media.next",
  "media.previous",
  "media.play_pause",
  "media.volume_up",
  "media.volume_down",
  "media.mute",
  "media.unmute",
  "media.volume",
]);

const DEFAULT_YANDEX_CLIENT_ID = "";

const DEVICE = {
  climate: "bydmate-car-1",
  autoClimate: "bydmate-climate-auto",
  recirculation: "bydmate-recirculation",
  rearDefrost: "bydmate-rear-defrost",
  frontDefrost: "bydmate-front-defrost",
  cabinVentilation: "bydmate-cabin-ventilation",
  fan: "bydmate-fan",
  airflowFace: "bydmate-airflow-face",
  airflowFaceFeet: "bydmate-airflow-face-feet",
  airflowFeet: "bydmate-airflow-feet",
  airflowFeetWindshield: "bydmate-airflow-feet-windshield",
  airflowWindshield: "bydmate-airflow-windshield",
  airflowFaceFeetWindshield: "bydmate-airflow-face-feet-windshield",
  airflowFaceWindshield: "bydmate-airflow-face-windshield",

  windowDriver: "bydmate-window-driver",
  windowPassenger: "bydmate-window-passenger",
  windowRearLeft: "bydmate-window-rear-left",
  windowRearRight: "bydmate-window-rear-right",
  allWindows: "bydmate-windows-all",
  windowsVent: "bydmate-windows-vent",

  interiorLight: "bydmate-interior-light",
  ambientLight: "bydmate-ambient-light",
  drl: "bydmate-drl",
  hazard: "bydmate-hazard",

  seatDriverHeat: "bydmate-seat-driver-heat",
  seatDriverVent: "bydmate-seat-driver-vent",
  seatPassengerHeat: "bydmate-seat-passenger-heat",
  seatPassengerVent: "bydmate-seat-passenger-vent",
  seatBothHeat: "bydmate-seat-both-heat",
  seatBothVent: "bydmate-seat-both-vent",

  locks: "bydmate-locks",
  rearTrunk: "bydmate-rear-trunk",
  frontTrunk: "bydmate-front-trunk",
  sunroof: "bydmate-sunroof",
  sunroofVent: "bydmate-sunroof-vent",
  sunroofTilt: "bydmate-sunroof-tilt",
  sunroofComfort: "bydmate-sunroof-comfort",
  sunroofStop: "bydmate-sunroof-stop",
  sunshade: "bydmate-sunshade",

  battery: "bydmate-battery",
  insideTemp: "bydmate-inside-temp",
  outsideTemp: "bydmate-outside-temp",

  waze: "bydmate-app-waze",
  yandexNavi: "bydmate-app-yandex-navi",
  yandexMaps: "bydmate-app-yandex-maps",
  music: "bydmate-app-music",
  youtube: "bydmate-app-youtube",
  browser: "bydmate-app-browser",
  carSettings: "bydmate-app-car-settings",
  androidSettings: "bydmate-app-android-settings",
  camera: "bydmate-app-camera",
  dashcam: "bydmate-app-dashcam",
  files: "bydmate-app-files",
  driveModes: "bydmate-app-drive-modes",
  sentry: "bydmate-app-sentry",
  abrp: "bydmate-app-abrp",
  mediaCenter: "bydmate-app-media-center",
  phone: "bydmate-app-phone",
  radio: "bydmate-app-radio",
  bydmate: "bydmate-app-self",
  tiktok: "bydmate-app-tiktok",

  clusterNavigation: "bydmate-navigation-cluster",

  media: "bydmate-media",
  mediaNext: "bydmate-media-next",
  mediaPrevious: "bydmate-media-previous",
};

let dbInitPromise = null;

function ensureDb(env) {
  if (!dbInitPromise) {
    dbInitPromise = initDb(env).catch((error) => {
      dbInitPromise = null;
      throw error;
    });
  }

  return dbInitPromise;
}


/* ======================================================
   BASIC HELPERS
   ====================================================== */

function json(data, status = 200) {
  return new Response(
    JSON.stringify(data),
    {
      status,
      headers: {
        "content-type":
          "application/json; charset=utf-8",
        "cache-control": "no-store",
      },
    }
  );
}

function authorized(request, env) {
  const supplied =
    request.headers.get("X-Api-Key") || "";

  return (
    supplied.length > 0 &&
    supplied === env.BYDMATE_API_KEY
  );
}

function requestId(request) {
  return (
    request.headers.get("X-Request-Id") ||
    crypto.randomUUID()
  );
}


/* ======================================================
   YANDEX OAUTH
   ====================================================== */

async function yandexUser(request, env) {
  const authorization =
    request.headers.get("Authorization") || "";

  const match =
    authorization.match(/^Bearer\s+(.+)$/i);

  if (!match) {
    return null;
  }

  try {
    const response = await fetch(
      "https://login.yandex.ru/info?format=json",
      {
        headers: {
          Authorization: `OAuth ${match[1]}`,
        },
      }
    );

    if (!response.ok) {
      return null;
    }

    const user = await response.json();

    if (!user.id) {
      return null;
    }

    const expectedClientId =
      String(env.YANDEX_CLIENT_ID || DEFAULT_YANDEX_CLIENT_ID).trim();

    if (
      !expectedClientId ||
      user.client_id !== expectedClientId
    ) {
      return null;
    }

    return user;
  } catch (error) {
    console.error(
      "Yandex OAuth validation failed",
      error
    );

    return null;
  }
}


/* ======================================================
   DATABASE
   ====================================================== */

async function initDb(env) {
  await env.DB.batch([
    env.DB.prepare(`
      CREATE TABLE IF NOT EXISTS commands (
        id TEXT PRIMARY KEY,
        action TEXT NOT NULL,
        value INTEGER,
        created_at INTEGER NOT NULL,
        acked INTEGER NOT NULL DEFAULT 0,
        success INTEGER,
        error TEXT
      )
    `),

    env.DB.prepare(`
      CREATE TABLE IF NOT EXISTS car_state (
        id INTEGER PRIMARY KEY,
        body TEXT NOT NULL,
        updated_at INTEGER NOT NULL
      )
    `),

    env.DB.prepare(`
      CREATE INDEX IF NOT EXISTS idx_commands_pending
      ON commands (acked, created_at)
    `),
  ]);
}

async function pendingCommands(env) {
  const result = await env.DB.prepare(`
    SELECT id, action, value
    FROM commands
    WHERE acked = 0
    ORDER BY created_at ASC
    LIMIT 30
  `).all();

  return result.results.map((row) => {
    const command = {
      id: row.id,
      action: row.action,
    };

    if (
      row.value !== null &&
      row.value !== undefined
    ) {
      command.value = row.value;
    }

    return command;
  });
}

async function enqueueCommand(
  env,
  action,
  value = null
) {
  if (!ALLOWED_ACTIONS.has(action)) {
    throw new Error("unsupported_action");
  }

  const id =
    crypto.randomUUID();

  const normalizedValue =
    value === undefined ||
    value === null
      ? null
      : Number(value);

  if (
    normalizedValue !== null &&
    !Number.isFinite(normalizedValue)
  ) {
    throw new Error("invalid_value");
  }

  await env.DB.prepare(`
    INSERT INTO commands (
    id,
      action,
      value,
      created_at
    )
    VALUES (?, ?, ?, ?)
  `)
    .bind(
      id,
      action,
      normalizedValue,
      Date.now()
    )
    .run();

  return {
    id,
    action,
    value: normalizedValue,
  };
}

async function enqueueMany(
  env,
  commands
) {
  const results = [];

  for (const command of commands) {
    results.push(
      await enqueueCommand(
        env,
        command.action,
        command.value
      )
    );
  }

  return results;
}

async function getCarState(env) {
  const row =
    await env.DB.prepare(`
      SELECT body, updated_at
      FROM car_state
      WHERE id = 1
    `).first();

  if (!row) {
    return null;
  }

  try {
    return {
      updated_at: row.updated_at,
      data: JSON.parse(row.body),
    };
  } catch {
    return null;
  }
}


/* ======================================================
   YANDEX CAPABILITIES / PROPERTIES
   ====================================================== */

function onOffCapability(
  retrievable = false
) {
  return {
    type:
      "devices.capabilities.on_off",
    retrievable,
    reportable: false,
  };
}

function modeCapability(
  instance,
  modes,
  retrievable = false
) {
  return {
    type:
      "devices.capabilities.mode",
    retrievable,
    reportable: false,
    parameters: {
      instance,
      modes:
        modes.map((value) => ({
          value,
        })),
    },
  };
}

function toggleCapability(
  instance,
  retrievable = false
) {
  return {
    type:
      "devices.capabilities.toggle",
    retrievable,
    reportable: false,
    parameters: {
      instance,
    },
  };
}

function rangeCapability(
  instance,
  min,
  max,
  precision = 1,
  unit = null,
  retrievable = false,
  randomAccess = true
) {
  const parameters = {
    instance,
    random_access:
      randomAccess,
    range: {
      min,
      max,
      precision,
    },
  };

  if (unit) {
    parameters.unit = unit;
  }

  return {
    type:
      "devices.capabilities.range",
    retrievable,
    reportable: false,
    parameters,
  };
}

function floatProperty(
  instance,
  unit
) {
  return {
    type:
      "devices.properties.float",
    retrievable: true,
    reportable: false,
    parameters: {
      instance,
      unit,
    },
  };
}

function baseDevice(
  id,
  name,
  description,
  type,
  capabilities = [],
  properties = []
) {
  return {
    id,
    name,
    description,
    room: "Машина",
    type,
    status_info: {
      reportable: false,
    },
    capabilities,
    properties,
    device_info: {
      manufacturer: "BYDMate",
      model: name,
      sw_version: "5.7",
    },
  };
}

function appDevice(
  id,
  name,
  description
) {
  return baseDevice(
    id,
    name,
    description,
    "devices.types.openable",
    [
      onOffCapability(false),
    ]
  );
}

function oneShotDevice(
  id,
  name,
  description
) {
  return baseDevice(
    id,
    name,
    description,
    "devices.types.switch",
    [
      onOffCapability(false),
    ]
  );
}


/* ======================================================
   DEVICE DESCRIPTIONS
   ====================================================== */

function climateDevice() {
  return baseDevice(
    DEVICE.climate,
    "Климат",
    "Климатическая система автомобиля BYD",
    "devices.types.thermostat.ac",
    [
      onOffCapability(true),
      rangeCapability(
        "temperature",
        16,
        30,
        1,
        "unit.temperature.celsius",
        true,
        true
      ),
    ]
  );
}

function fanDevice() {
  return baseDevice(
    DEVICE.fan,
    "Обдув BYD",
    "Обдув и скорость вентилятора климатической системы BYD",
    "devices.types.ventilation.fan",
    [
      onOffCapability(false),

      modeCapability(
        "fan_speed",
        [
          "low",
          "medium",
          "high",
          "turbo",
        ],
        false
      ),
    ]
  );
}

function seatHeatDevice(
  id,
  name,
  description
) {
  return baseDevice(
    id,
    name,
    description,
    "devices.types.switch",
    [
      onOffCapability(false),

      modeCapability(
        "heat",
        [
          "min",
          "max",
        ],
        false
      ),
    ]
  );
}

function seatVentDevice(
  id,
  name,
  description
) {
  return baseDevice(
    id,
    name,
    description,
    "devices.types.ventilation.fan",
    [
      onOffCapability(false),

      modeCapability(
        "fan_speed",
        [
          "low",
          "high",
        ],
        false
      ),
    ]
  );
}

function windowDevice(
  id,
  name,
  description
) {
  return baseDevice(
    id,
    name,
    description,
    "devices.types.openable",
    [
      onOffCapability(true),

      rangeCapability(
        "open",
        0,
        100,
        1,
        "unit.percent",
        true,
        true
      ),
    ]
  );
}

function yandexDevices() {
  return [
    // Climate
    climateDevice(),

    baseDevice(
      DEVICE.recirculation,
      "Рециркуляция",
      "Рециркуляция воздуха в салоне BYD",
      "devices.types.switch",
      [
        onOffCapability(true),
      ]
    ),

    fanDevice(),

    oneShotDevice(
      DEVICE.airflowFace,
      "Воздух в лицо",
      "Направить поток климатической системы BYD в лицо"
    ),

    oneShotDevice(
      DEVICE.airflowFaceFeet,
      "Воздух в лицо и ноги",
      "Направить поток климатической системы BYD в лицо и ноги"
    ),

    oneShotDevice(
      DEVICE.airflowFeet,
      "Воздух в ноги",
      "Направить поток климатической системы BYD в ноги"
    ),

    oneShotDevice(
      DEVICE.airflowFeetWindshield,
      "Воздух в ноги и на стекло",
      "Направить поток климатической системы BYD в ноги и на лобовое стекло"
    ),

    oneShotDevice(
      DEVICE.airflowWindshield,
      "Воздух наверх",
      "Направить поток климатической системы BYD на лобовое стекло"
    ),

    oneShotDevice(
      DEVICE.airflowFaceFeetWindshield,
      "Воздух в лицо, ноги и наверх",
      "Направить поток климатической системы BYD одновременно в лицо, ноги и на лобовое стекло"
    ),

    oneShotDevice(
      DEVICE.airflowFaceWindshield,
      "Воздух в лицо и наверх",
      "Направить поток климатической системы BYD одновременно в лицо и на лобовое стекло"
    ),

    baseDevice(
      DEVICE.frontDefrost,
      "Разморозка лобового стекла",
      "Интенсивная разморозка лобового стекла BYD",
      "devices.types.switch",
      [
        onOffCapability(true),
      ]
    ),

    baseDevice(
      DEVICE.rearDefrost,
      "Обогрев заднего стекла",
      "Обогрев заднего стекла и зеркал BYD",
      "devices.types.switch",
      [
        onOffCapability(false),
      ]
    ),

    // Windows
    windowDevice(
      DEVICE.windowDriver,
      "Окно водителя",
      "Переднее водительское окно BYD"
    ),

    windowDevice(
      DEVICE.windowPassenger,
      "Окно пассажира",
      "Переднее пассажирское окно BYD"
    ),

    windowDevice(
      DEVICE.windowRearLeft,
      "Заднее левое окно",
      "Заднее левое окно BYD"
    ),

    windowDevice(
      DEVICE.windowRearRight,
      "Заднее правое окно",
      "Заднее правое окно BYD"
    ),

    baseDevice(
      DEVICE.allWindows,
      "Все окна",
      "Все четыре окна BYD",
      "devices.types.openable",
      [
        onOffCapability(false),

        rangeCapability(
          "open",
          0,
          100,
          1,
          "unit.percent",
          false,
          true
        ),
      ]
    ),

    oneShotDevice(
      DEVICE.windowsVent,
      "Проветри машину",
      "Приоткрыть все окна для проветривания"
    ),

    // Seats
    seatHeatDevice(
      DEVICE.seatDriverHeat,
      "Подогрев водителя",
      "Подогрев водительского сиденья BYD"
    ),

    seatVentDevice(
      DEVICE.seatDriverVent,
      "Вентиляция водителя",
      "Вентиляция водительского сиденья BYD"
    ),

    seatHeatDevice(
      DEVICE.seatPassengerHeat,
      "Подогрев пассажира",
      "Подогрев пассажирского сиденья BYD"
    ),

    seatVentDevice(
      DEVICE.seatPassengerVent,
      "Вентиляция пассажира",
      "Вентиляция пассажирского сиденья BYD"
    ),

    seatHeatDevice(
      DEVICE.seatBothHeat,
      "Подогрев сидений",
      "Подогрев водительского и пассажирского сидений BYD"
    ),

    seatVentDevice(
      DEVICE.seatBothVent,
      "Вентиляция сидений",
      "Вентиляция водительского и пассажирского сидений BYD"
    ),

    // Lights
    baseDevice(
      DEVICE.interiorLight,
      "Свет салона",
      "Основной свет салона BYD",
      "devices.types.light",
      [
        onOffCapability(false),
      ]
    ),

    baseDevice(
      DEVICE.ambientLight,
      "Подсветка салона",
      "Ambient-подсветка салона BYD",
      "devices.types.light",
      [
        onOffCapability(false),
      ]
    ),

    // Body

    baseDevice(
      DEVICE.rearTrunk,
      "Багажник",
      "Задний багажник BYD",
      "devices.types.openable",
      [
        onOffCapability(true),
      ]
    ),

    baseDevice(
      DEVICE.sunroof,
      "Люк",
      "Панорамный люк BYD",
      "devices.types