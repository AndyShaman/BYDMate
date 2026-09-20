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
      "devices.types.openable",
      [
        onOffCapability(true),
      ]
    ),

    baseDevice(
      DEVICE.sunroof,
      "Люк",
      "Панорамный люк BYD",
      "devices.types.openable",
      [
        onOffCapability(true),

        rangeCapability(
          "open",
          0,
          100,
          10,
          "unit.percent",
          false,
          true
        ),
      ]
    ),
    
    oneShotDevice(
      DEVICE.sunroofVent,
      "Проветривание крыши",
      "Приподнять панорамный люк для проветривания"
    ),

    oneShotDevice(
      DEVICE.sunroofComfort,
      "Комфортное открытие крыши",
      "Комфортное открытие панорамной крыши BYD"
    ),

    baseDevice(
      DEVICE.sunshade,
      "Шторка",
      "Шторка панорамной крыши BYD",
      "devices.types.openable",
      [
        onOffCapability(false),
      ]
    ),

    // Sensors
    baseDevice(
      DEVICE.battery,
      "Заряд батареи",
      "Уровень заряда тяговой батареи BYD",
      "devices.types.sensor",
      [],
      [
        floatProperty(
          "battery_level",
          "unit.percent"
        ),
      ]
    ),

    baseDevice(
      DEVICE.insideTemp,
      "Датчик салона",
      "Температура воздуха в салоне BYD",
      "devices.types.sensor.climate",
      [],
      [
        floatProperty(
          "temperature",
          "unit.temperature.celsius"
        ),
      ]
    ),

    baseDevice(
      DEVICE.outsideTemp,
      "Наружный датчик машины",
      "Наружная температура автомобиля BYD",
      "devices.types.sensor.climate",
      [],
      [
        floatProperty(
          "temperature",
          "unit.temperature.celsius"
        ),
      ]
    ),

    // Applications
    appDevice(
      DEVICE.waze,
      "Навигация",
      "Открыть навигацию на экране автомобиля"
    ),

    appDevice(
      DEVICE.music,
      "Яндекс Музыка",
      "Открыть Яндекс Музыку"
    ),

    appDevice(
      DEVICE.youtube,
      "YouTube Premium",
      "Открыть YouTube"
    ),

    appDevice(
      DEVICE.browser,
      "Браузер",
      "Открыть браузер автомобиля"
    ),

    appDevice(
      DEVICE.carSettings,
      "Настройки машины",
      "Открыть штатные настройки автомобиля BYD"
    ),

    appDevice(
      DEVICE.camera,
      "Камера 360",
      "Открыть штатную систему кругового обзора BYD"
    ),

    appDevice(
      DEVICE.dashcam,
      "Регистратор",
      "Открыть штатный видеорегистратор BYD"
    ),

    appDevice(
      DEVICE.files,
      "Файлы",
      "Открыть файловый менеджер BYD"
    ),

    appDevice(
      DEVICE.sentry,
      "Сэнтри",
      "Открыть выбранное приложение охранного режима"
    ),

    appDevice(
      DEVICE.abrp,
      "ABRP",
      "Открыть A Better Routeplanner"
    ),

    appDevice(
      DEVICE.mediaCenter,
      "Медиацентр",
      "Открыть штатный медиацентр BYD"
    ),

    appDevice(
      DEVICE.tiktok,
      "TikTok",
      "Открыть приложение TikTok"
    ),

    // Navigation projection
    baseDevice(
      DEVICE.clusterNavigation,
      "Навигатор на приборке",
      "Проекция выбранного навигатора на приборную панель",
      "devices.types.switch",
      [
        onOffCapability(false),
      ]
    ),

    // Media controls
    baseDevice(
      DEVICE.media,
      "Медиа",
      "Управление воспроизведением и громкостью автомобиля",
      "devices.types.media_device",
      [
        onOffCapability(false),

        toggleCapability(
          "pause",
          false
        ),

        toggleCapability(
          "mute",
          false
        ),

        rangeCapability(
          "volume",
          0,
          100,
          5,
          "unit.percent",
          false,
          true
        ),
      ]
    ),

    oneShotDevice(
      DEVICE.mediaNext,
      "Следующая",
      "Переключить на следующий трек"
    ),

    oneShotDevice(
      DEVICE.mediaPrevious,
      "Предыдущая",
      "Переключить на предыдущий трек"
    ),
  ];
}


/* ======================================================
   QUERY HELPERS
   ====================================================== */

function onOffState(value) {
  return {
    type:
      "devices.capabilities.on_off",

    state: {
      instance: "on",
      value: Boolean(value),
    },
  };
}

function rangeState(
  instance,
  value
) {
  return {
    type:
      "devices.capabilities.range",

    state: {
      instance,
      value:
        Number(value),
    },
  };
}

function modeState(
  instance,
  value
) {
  return {
    type:
      "devices.capabilities.mode",

    state: {
      instance,
      value,
    },
  };
}

function floatPropertyState(
  instance,
  value
) {
  return {
    type:
      "devices.properties.float",

    state: {
      instance,
      value:
        Number(value),
    },
  };
}

function fanModeFromLevel(
  level
) {
  const value =
      Number(level);

  if (!Number.isFinite(value)) {
    return null;
  }

  if (value >= 7) {
    return "turbo";
  }

  if (value >= 5) {
    return "high";
  }

  if (value >= 3) {
    return "medium";
  }

  if (value >= 1) {
    return "low";
  }

  return null;
}

const APP_ACTIONS = {
  [DEVICE.waze]:
    "app.navigation.open",

  [DEVICE.yandexNavi]:
    "app.yandex_navi.open",

  [DEVICE.yandexMaps]:
    "app.yandex_maps.open",

  [DEVICE.music]:
    "app.music.open",

  [DEVICE.youtube]:
    "app.youtube.open",

  [DEVICE.browser]:
    "app.browser.open",

  [DEVICE.carSettings]:
    "app.car_settings.open",

  [DEVICE.androidSettings]:
    "app.android_settings.open",

  [DEVICE.camera]:
    "app.camera.open",

  [DEVICE.dashcam]:
    "app.dashcam.open",

  [DEVICE.files]:
    "app.files.open",

  [DEVICE.driveModes]:
    "app.drive_modes.open",

  [DEVICE.sentry]:
    "app.sentry.open",

  [DEVICE.abrp]:
    "app.abrp.open",

  [DEVICE.mediaCenter]:
    "app.media_center.open",

  [DEVICE.phone]:
    "app.phone.open",

  [DEVICE.radio]:
    "app.radio.open",

  [DEVICE.bydmate]:
    "app.bydmate.open",

  [DEVICE.tiktok]:
    "app.tiktok.open",
};

const ONE_SHOT_ACTIONS = {
  [DEVICE.airflowFace]:
    "climate.airflow_face",

  [DEVICE.airflowFaceFeet]:
    "climate.airflow_face_feet",

  [DEVICE.airflowFeet]:
    "climate.airflow_feet",

  [DEVICE.airflowFeetWindshield]:
    "climate.airflow_feet_windshield",

  [DEVICE.airflowWindshield]:
    "climate.airflow_windshield",

  [DEVICE.airflowFaceFeetWindshield]:
    "climate.airflow_face_feet_windshield",

  [DEVICE.airflowFaceWindshield]:
    "climate.airflow_face_windshield",

  [DEVICE.windowsVent]:
    "window.all.vent",

  [DEVICE.sunroofComfort]:
    "sunroof.comfort",

  [DEVICE.sunroofStop]:
    "sunroof.stop",

  [DEVICE.mediaNext]:
    "media.next",

  [DEVICE.mediaPrevious]:
    "media.previous",
};

function isActionOnlyDevice(
  id
) {
  return (
    Object.prototype.hasOwnProperty.call(
      APP_ACTIONS,
      id
    ) ||
    Object.prototype.hasOwnProperty.call(
      ONE_SHOT_ACTIONS,
      id
    ) ||
    id ===
      DEVICE.fan ||
    id ===
      DEVICE.rearDefrost ||
    id ===
      DEVICE.cabinVentilation ||
    id ===
      DEVICE.interiorLight ||
    id ===
      DEVICE.ambientLight ||
    id ===
      DEVICE.sunshade ||
    id ===
      DEVICE.clusterNavigation ||
    id ===
      DEVICE.media ||
    id ===
      DEVICE.seatDriverHeat ||
    id ===
      DEVICE.seatDriverVent ||
    id ===
      DEVICE.seatPassengerHeat ||
    id ===
      DEVICE.seatPassengerVent ||
    id ===
      DEVICE.seatBothHeat ||
    id ===
      DEVICE.seatBothVent ||
    id ===
      DEVICE.allWindows
  );
}

function queryDevice(
  id,
  carState
) {
  /*
   * Action-only devices do not depend on fresh vehicle telemetry.
   */
  if (
    isActionOnlyDevice(
      id
    )
  ) {
    return {
      id,
      capabilities: [],
      properties: [],
    };
  }

  if (!carState) {
    return {
      id,

      error_code:
        "DEVICE_UNREACHABLE",

      error_message:
        "BYDMate has not reported vehicle state yet",
    };
  }

  const data =
    carState.data || {};


  /*
   * Sensors
   */
  if (
    id ===
      DEVICE.battery
  ) {
    const soc =
      Number(
        data.soc
      );

    if (
      !Number.isFinite(
        soc
      )
    ) {
      return {
        id,

        error_code:
          "DEVICE_UNREACHABLE",

        error_message:
          "Battery SOC is unavailable",
      };
    }

    return {
      id,

      capabilities: [],

      properties: [
        floatPropertyState(
          "battery_level",
          Math.max(
            0,
            Math.min(
              100,
              soc
            )
          )
        ),
      ],
    };
  }

  if (
    id ===
      DEVICE.insideTemp ||
    id ===
      DEVICE.outsideTemp
  ) {
    const value =
      Number(
        id ===
          DEVICE.insideTemp
          ? data.insideTemp
          : data.exteriorTemp
      );

    if (
      !Number.isFinite(
        value
      )
    ) {
      return {
        id,

         error_code:
          "DEVICE_UNREACHABLE",

        error_message:
          "Temperature is unavailable",
      };
    }

    return {
      id,

      capabilities: [],

      properties: [
        floatPropertyState(
          "temperature",
          value
        ),
      ],
    };
  }


  /*
   * Climate
   */
  if (
    id === DEVICE.climate
  ) {
    const capabilities = [
      onOffState(
        Number(
          data.acStatus
        ) === 1
      ),
    ];

    if (
      data.acTemp !== undefined &&
      data.acTemp !== null &&
      Number.isFinite(
        Number(
          data.acTemp
        )
      )
    ) {
      capabilities.push(
        rangeState(
          "temperature",
          Number(
            data.acTemp
          )
        )
      );
    }

    return {
      id,
      capabilities,
      properties: [],
    };
  }

  if (
    id ===
      DEVICE.autoClimate
  ) {
    return {
      id,

      capabilities: [
        onOffState(
          Number(
            data.acCtrlMode
          ) === 0
        ),
      ],

      properties: [],
    };
  }

  if (
    id ===
      DEVICE.recirculation
  ) {
    return {
      id,

      capabilities: [
        onOffState(
          Number(
            data.acCirc
          ) === 1
        ),
      ],

      properties: [],
    };
  }

  if (
    id ===
      DEVICE.frontDefrost
  ) {
    return {
      id,

      capabilities: [
        onOffState(
          Number(
            data.acDefrostFront
          ) === 1
        ),
      ],

      properties: [],
    };
  }

  if (
    id ===
      DEVICE.fan
  ) {
    const level =
      Number(
        data.fanLevel
      );

    const capabilities = [];

    if (
      Number.isFinite(
        level
      )
    ) {
      capabilities.push(
        onOffState(
          level > 0
        )
      );

      const mode =
        fanModeFromLevel(
          level
        );

      if (mode) {
        capabilities.push(
          modeState(
            "fan_speed",
            mode
          )
        );
      }
    }

    return {
      id,
      capabilities,
      properties: [],
    };
  }


  /*
   * Windows
   */
  const windowStates = {
    [DEVICE.windowDriver]:
      data.windowFL,

    [DEVICE.windowPassenger]:
      data.windowFR,

    [DEVICE.windowRearLeft]:
      data.windowRL,

    [DEVICE.windowRearRight]:
      data.windowRR,
  };

  if (
    Object.prototype.hasOwnProperty.call(
      windowStates,
      id
    )
  ) {
    const position =
      Number(
        windowStates[id]
      );

    if (
      !Number.isFinite(
        position
      )
    ) {
      return {
        id,

        error_code:
          "DEVICE_UNREACHABLE",

        error_message:
          "Window position is unavailable",
      };
    }

    return {
      id,

      capabilities: [
        onOffState(
          position > 0
        ),

        rangeState(
          "open",
          Math.max(
            0,
            Math.min(
              100,
              position
            )
          )
        ),
      ],

      properties: [],
    };
  }


  /*
   * Body
   */
  if (
    id ===
      DEVICE.locks
  ) {
    return {
      id,

      capabilities: [
        // openable ON = unlocked/open
        onOffState(
          Number(
            data.lockFL
          ) === 1
        ),
      ],

      properties: [],
    };
  }

  if (
    id ===
      DEVICE.rearTrunk
  ) {
    return {
      id,

      capabilities: [
        onOffState(
          Number(
            data.trunk
          ) === 1
        ),
      ],

      properties: [],
    };
  }

  if (
    id ===
      DEVICE.frontTrunk
  ) {
    return {
      id,

      capabilities: [
        onOffState(
          Number(
            data.hood
          ) === 1
        ),
      ],

      properties: [],
    };
  }

  if (
    id ===
      DEVICE.sunroof
  ) {
    const value =
      Number(
        data.sunroof
      );

    return {
      id,

      capabilities: [
        onOffState(
          Number.isFinite(
            value
          ) &&
          value > 0
        ),
      ],

      properties: [],
    };
  }

  if (
    id ===
      DEVICE.drl
  ) {
    return {
      id,

      capabilities: [
        onOffState(
          Number(
            data.drl
          ) === 1
        ),
      ],

      properties: [],
    };
  }

  if (
    id ===
      DEVICE.hazard
  ) {
    return {
      id,

      capabilities: [
        onOffState(
          Number(
            data.turnSignal
          ) === 6
        ),
      ],

      properties: [],
    };
  }


  return {
    id,

    error_code:
      "DEVICE_NOT_FOUND",

    error_message:
      "BYDMate device not found",
  };
}


/* ======================================================
   ACTION RESPONSE HELPERS
   ====================================================== */

function actionDone(
  type,
  instance
) {
  return {
    type,

    state: {
      instance,

      action_result: {
        status: "DONE",
      },
    },
  };
}

function actionError(
  type,
  instance,
  code,
  message
) {
  return {
    type,

    state: {
      instance,

      action_result: {
        status: "ERROR",

        error_code:
          code,

        error_message:
          message,
      },
    },
  };
}


/* ======================================================
   CLIMATE ACTION
   ====================================================== */

async function handleClimateAction(
  env,
  capability,
  carState
) {
  const type =
    capability.type || "";

  const state =
    capability.state || {};

  if (
    type ===
      "devices.capabilities.on_off" &&
    state.instance === "on"
  ) {
    await enqueueCommand(
      env,

      Boolean(
        state.value
      )
        ? "climate.on"
        : "climate.off"
    );

    return actionDone(
      type,
      "on"
    );
  }

  if (
    type ===
      "devices.capabilities.range" &&
    state.instance ===
      "temperature"
  ) {
    let temperature =
      Number(
        state.value
      );

    if (
      state.relative
    ) {
      const current =
        carState &&
        carState.data
          ? Number(
              carState.data.acTemp
            )
          : NaN;

      if (
        !Number.isFinite(
          current
        )
      ) {
        return actionError(
          type,
          "temperature",
          "DEVICE_UNREACHABLE",
          "Current climate temperature is unavailable"
        );
      }

      temperature =
        current +
        temperature;
    }

    if (
      !Number.isFinite(
        temperature
      )
    ) {
      return actionError(
        type,
        "temperature",
        "INVALID_ACTION",
        "Invalid temperature"
      );
    }

    temperature =
      Math.max(
        16,
        Math.min(
          30,
          Math.round(
            temperature
          )
        )
      );

    await enqueueCommand(
      env,
      "climate.temperature",
      temperature
    );

    return actionDone(
      type,
      "temperature"
    );
  }

  return actionError(
    type,
    state.instance ||
      "unknown",
    "INVALID_ACTION",
    "Capability is not supported by BYDMate"
  );
}


/* ======================================================
   FAN / VENTILATION ACTION
   ====================================================== */

const FAN_LEVEL_BY_MODE = {
  low: 1,
  medium: 3,
  high: 5,
  turbo: 7,
};

async function handleFanAction(
  env,
  capability
) {
  const type =
    capability.type || "";

  const state =
    capability.state || {};

  if (
    type ===
      "devices.capabilities.on_off" &&
    state.instance === "on"
  ) {
    await enqueueCommand(
      env,

      Boolean(
        state.value
      )
        ? "climate.flow_only_on"
        : "climate.flow_only_off"
    );

    return actionDone(
      type,
      "on"
    );
  }

  if (
    type ===
      "devices.capabilities.mode" &&
    state.instance ===
      "fan_speed"
  ) {
    const level =
      FAN_LEVEL_BY_MODE[
        state.value
      ];

    if (
      !level
    ) {
      return actionError(
        type,
        "fan_speed",
        "INVALID_ACTION",
        "Unsupported fan speed"
      );
    }

    await enqueueCommand(
      env,
      "climate.fan_level",
      level
    );

    return actionDone(
      type,
      "fan_speed"
    );
  }

  return actionError(
    type,
    state.instance ||
      "unknown",
    "INVALID_ACTION",
    "Fan capability is not supported"
  );
}


/* ======================================================
   WINDOW ACTIONS
   ====================================================== */

const WINDOW_ACTION_PREFIX = {
  [DEVICE.windowDriver]:
    "window.driver",

  [DEVICE.windowPassenger]:
    "window.passenger",

  [DEVICE.windowRearLeft]:
    "window.rear_left",

  [DEVICE.windowRearRight]:
    "window.rear_right",
};

async function handleWindowAction(
  env,
  deviceId,
  capability
) {
  const prefix =
    WINDOW_ACTION_PREFIX[
      deviceId
    ];

  const type =
    capability.type || "";

  const state =
    capability.state || {};

  if (!prefix) {
    return actionError(
      type,
      state.instance ||
        "unknown",
      "DEVICE_NOT_FOUND",
      "Window device not found"
    );
  }

  if (
    type ===
      "devices.capabilities.on_off" &&
    state.instance === "on"
  ) {
    await enqueueCommand(
      env,

      Boolean(
        state.value
      )
        ? `${prefix}.open`
        : `${prefix}.close`
    );

    return actionDone(
      type,
      "on"
    );
  }

  if (
    type ===
      "devices.capabilities.range" &&
    state.instance === "open"
  ) {
    const value =
      Math.max(
        0,
        Math.min(
          100,
          Math.round(
            Number(
              state.value
            )
          )
        )
      );

    if (
      !Number.isFinite(
        value
      )
    ) {
      return actionError(
        type,
        "open",
        "INVALID_ACTION",
        "Invalid window position"
      );
    }

    await enqueueCommand(
      env,
      `${prefix}.position`,
      value
    );

    return actionDone(
      type,
      "open"
    );
  }

  return actionError(
    type,
    state.instance ||
      "unknown",
    "INVALID_ACTION",
    "Window capability is not supported"
  );
}

async function handleAllWindowsAction(
  env,
  capability
) {
  const type =
    capability.type || "";

  const state =
    capability.state || {};

  if (
    type ===
      "devices.capabilities.on_off" &&
    state.instance === "on"
  ) {
    await enqueueCommand(
      env,

      Boolean(
        state.value
      )
        ? "window.all.open"
        : "window.all.close"
    );

    return actionDone(
      type,
      "on"
    );
  }

  if (
    type ===
      "devices.capabilities.range" &&
    state.instance === "open"
  ) {
    const raw =
      Number(
        state.value
      );

    if (
      !Number.isFinite(
        raw
      )
    ) {
      return actionError(
        type,
        "open",
        "INVALID_ACTION",
        "Invalid window position"
      );
    }

    const value =
      Math.max(
        0,
        Math.min(
          100,
          Math.round(
            raw
          )
        )
      );

    if (value === 0) {
      await enqueueCommand(
        env,
        "window.all.close"
      );
    } else if (
      value === 50
    ) {
      await enqueueCommand(
        env,
        "window.all.half"
      );
    } else if (
      value === 100
    ) {
      await enqueueCommand(
        env,
        "window.all.open"
      );
    } else {
      await enqueueMany(
        env,
        [
          {
            action:
              "window.driver.position",
            value,
          },
          {
            action:
              "window.passenger.position",
            value,
          },
          {
            action:
              "window.rear_left.position",
            value,
          },
          {
            action:
              "window.rear_right.position",
            value,
          },
        ]
      );
    }

    return actionDone(
      type,
      "open"
    );
  }

  return actionError(
    type,
    state.instance ||
      "unknown",
    "INVALID_ACTION",
    "All-windows capability is not supported"
  );
}


/* ======================================================
   SEAT ACTIONS
   ====================================================== */

const SEAT_ACTIONS = {
  [DEVICE.seatDriverHeat]: {
    actions: [
      "seat.driver.heat",
    ],
    instance: "heat",
    weak: "min",
    strong: "max",
  },

  [DEVICE.seatPassengerHeat]: {
    actions: [
      "seat.passenger.heat",
    ],
    instance: "heat",
    weak: "min",
    strong: "max",
  },

  [DEVICE.seatDriverVent]: {
    actions: [
      "seat.driver.vent",
    ],
    instance: "fan_speed",
    weak: "low",
    strong: "high",
  },

  [DEVICE.seatPassengerVent]: {
    actions: [
      "seat.passenger.vent",
    ],
    instance: "fan_speed",
    weak: "low",
    strong: "high",
  },

  [DEVICE.seatBothHeat]: {
    actions: [
      "seat.driver.heat",
      "seat.passenger.heat",
    ],
    instance: "heat",
    weak: "min",
    strong: "max",
  },

  [DEVICE.seatBothVent]: {
    actions: [
      "seat.driver.vent",
      "seat.passenger.vent",
    ],
    instance: "fan_speed",
    weak: "low",
    strong: "high",
  },
};

async function enqueueSeatLevel(
  env,
  actions,
  level
) {
  await enqueueMany(
    env,
    actions.map(
      (action) => ({
        action,
        value: level,
      })
    )
  );
}

async function handleSeatAction(
  env,
  deviceId,
  capability
) {
  const config =
    SEAT_ACTIONS[
      deviceId
    ];

  if (!config) {
    return actionError(
      capability.type ||
        "",
      capability.state
        ?.instance ||
        "unknown",
      "DEVICE_NOT_FOUND",
      "Seat device not found"
    );
  }

  const type =
    capability.type || "";

  const state =
    capability.state || {};

  if (
    type ===
      "devices.capabilities.on_off" &&
    state.instance === "on"
  ) {
    const level =
      Boolean(
        state.value
      )
        ? 1
        : 0;

    await enqueueSeatLevel(
      env,
      config.actions,
      level
    );

    return actionDone(
      type,
      "on"
    );
  }

  if (
    type ===
      "devices.capabilities.mode" &&
    state.instance ===
      config.instance
  ) {
    let level;

    if (
      state.value ===
        config.weak
    ) {
      level = 1;
    } else if (
      state.value ===
        config.strong
    ) {
      level = 2;
    } else {
      return actionError(
        type,
        config.instance,
        "INVALID_ACTION",
        "Unsupported seat comfort level"
      );
    }

    await enqueueSeatLevel(
      env,
      config.actions,
      level
    );

    return actionDone(
      type,
      config.instance
    );
  }

  return actionError(
    type,
    state.instance ||
      "unknown",
    "INVALID_ACTION",
    "Seat capability is not supported by BYDMate"
  );
}


/* ======================================================
   SIMPLE BINARY VEHICLE DEVICES
   ====================================================== */
   
async function handleSunroofAction(
  env,
  capability
) {
  const type =
    capability.type || "";

  const state =
    capability.state || {};

  if (
    type ===
      "devices.capabilities.on_off" &&
    state.instance === "on"
  ) {
    await enqueueCommand(
      env,
      Boolean(state.value)
        ? "sunroof.open"
        : "sunroof.close"
    );

    return actionDone(
      type,
      "on"
    );
  }

  if (
    type ===
      "devices.capabilities.range" &&
    state.instance === "open"
  ) {
    const raw =
      Number(state.value);

    if (!Number.isFinite(raw)) {
      return actionError(
        type,
        "open",
        "INVALID_ACTION",
        "Invalid sunroof position"
      );
    }

    // Yandex exposes 10% steps. Keep 0/50/100 on the car's native
    // detents; every other step is positioned locally by BYDMate 5.4
    // using live sunroof percentage readback + STOP at the target.
    const value = Math.max(
      0,
      Math.min(
        100,
        Math.round(raw / 10) * 10
      )
    );

    if (value === 0) {
      await enqueueCommand(
        env,
        "sunroof.close"
      );
    } else if (value === 50) {
      await enqueueCommand(
        env,
        "sunroof.tilt"
      );
    } else if (value === 100) {
      await enqueueCommand(
        env,
        "sunroof.open"
      );
    } else {
      await enqueueCommand(
        env,
        "sunroof.position",
        value
      );
    }

    return actionDone(
      type,
      "open"
    );
  }

  return actionError(
    type,
    state.instance || "unknown",
    "INVALID_ACTION",
    "Sunroof capability is not supported"
  );
}

const BINARY_ACTIONS = {
  [DEVICE.autoClimate]: {
    on:
      "climate.auto_on",
    off:
      "climate.auto_off",
  },

  [DEVICE.recirculation]: {
    on:
      "climate.recirculation_inner",
    off:
      "climate.recirculation_outer",
  },

  [DEVICE.frontDefrost]: {
    on:
      "climate.front_defrost_on",
    off:
      "climate.front_defrost_off",
  },

  [DEVICE.rearDefrost]: {
    on:
      "climate.rear_defrost_on",
    off:
      "climate.rear_defrost_off",
  },

  [DEVICE.cabinVentilation]: {
    on:
      "climate.flow_only_on",
    off:
      "climate.flow_only_off",
  },

  [DEVICE.interiorLight]: {
    on:
      "light.interior_on",
    off:
      "light.interior_off",
  },

  [DEVICE.ambientLight]: {
    on:
      "light.ambient_on",
    off:
      "light.ambient_off",
  },

  [DEVICE.drl]: {
    on:
      "light.drl_on",
    off:
      "light.drl_off",
  },

  [DEVICE.hazard]: {
    on:
      "light.hazard_on",
    off:
      "light.hazard_off",
  },

  // For openable car