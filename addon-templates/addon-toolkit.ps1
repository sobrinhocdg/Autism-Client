param(
    [ValidateSet("Menu", "Setup", "Scan", "Validate", "PublishApi", "Build", "BuildAll", "Clean")]
    [string] $Action = "Menu",

    [ValidateSet("minimal", "advanced")]
    [string] $Template = "minimal",

    [string] $ProjectPath,
    [string] $OutputPath,
    [string] $AddonId,
    [string] $AddonName,
    [string] $AddonVersion,
    [string] $Package,
    [string] $Author,
    [string] $Description,
    [string] $MavenGroup,
    [string] $ArchiveName,
    [switch] $Advanced,
    [switch] $DebugStack,
    [switch] $NoPublish,
    [switch] $Yes,
    [switch] $NonInteractive
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot

function Write-Info([string] $Message) {
    Write-Host "[AUTISM] $Message" -ForegroundColor DarkGray
}

function Write-Ok([string] $Message) {
    Write-Host "[OK] $Message" -ForegroundColor Green
}

function Write-Warn([string] $Message) {
    Write-Host "[WARN] $Message" -ForegroundColor DarkYellow
}

function Write-Bad([string] $Message) {
    Write-Host "[FAIL] $Message" -ForegroundColor White -BackgroundColor Red
}

function Write-PathLine([string] $Label, [string] $Path) {
    Write-Host "${Label}:" -ForegroundColor DarkGray
    Write-Host $Path -ForegroundColor White
}

function Write-FailureItem([string] $Message) {
    if ($Message -match "^[A-Za-z]:[\\/]" -or $Message -match "^/" -or $Message -match "^[.]{1,2}[\\/]") {
        Write-PathLine "Path" $Message
    } else {
        Write-Bad " - $Message"
    }
}

function Resolve-FullPath([string] $Path) {
    if ([string]::IsNullOrWhiteSpace($Path)) {
        return $null
    }
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $RepoRoot $Path))
}

function Test-ShippedTemplatePath([string] $Path) {
    if ([string]::IsNullOrWhiteSpace($Path)) {
        return $false
    }
    $full = [System.IO.Path]::GetFullPath($Path).TrimEnd("\", "/")
    foreach ($templateName in @("minimal", "advanced")) {
        $templatePath = [System.IO.Path]::GetFullPath((Join-Path $RepoRoot "addon-templates/$templateName")).TrimEnd("\", "/")
        if ([string]::Equals($full, $templatePath, [System.StringComparison]::OrdinalIgnoreCase)) {
            return $true
        }
    }
    return $false
}

function ConvertTo-FolderName([string] $Name) {
    if ([string]::IsNullOrWhiteSpace($Name)) {
        return "MyAddon"
    }
    $parts = [regex]::Matches($Name, "[A-Za-z0-9]+") | ForEach-Object { $_.Value }
    if (-not $parts -or $parts.Count -eq 0) {
        return "MyAddon"
    }
    $folder = (($parts | ForEach-Object {
        if ($_.Length -eq 1) {
            $_.ToUpperInvariant()
        } else {
            $_.Substring(0, 1).ToUpperInvariant() + $_.Substring(1)
        }
    }) -join "")
    if ($folder.Length -gt 48) {
        $folder = $folder.Substring(0, 48)
    }
    return $folder
}

function ConvertTo-PackageSegment([string] $Value) {
    $segment = $Value.ToLowerInvariant() -replace "[^a-z0-9]+", ""
    if ([string]::IsNullOrWhiteSpace($segment)) {
        $segment = "myaddon"
    }
    if ($segment[0] -notmatch "[a-z]") {
        $segment = "addon$segment"
    }
    return $segment
}

function Get-DefaultAuthor {
    if (-not [string]::IsNullOrWhiteSpace($env:USERNAME)) {
        return $env:USERNAME
    }
    return "You"
}

function Get-DefaultAddonOutputPath([string] $TemplateName, [string] $Name) {
    $baseName = if ([string]::IsNullOrWhiteSpace($Name)) {
        if ($TemplateName -eq "advanced") { "MyAdvancedAddon" } else { "MyAddon" }
    } else {
        ConvertTo-FolderName $Name
    }
    return Resolve-FullPath (Join-Path ".." $baseName)
}

function Read-Value([string] $Prompt, [string] $Default, [scriptblock] $Validator) {
    while ($true) {
        $suffix = if ([string]::IsNullOrWhiteSpace($Default)) { "" } else { " [$Default]" }
        $value = Read-Host "$Prompt$suffix"
        if ([string]::IsNullOrWhiteSpace($value)) {
            $value = $Default
        }
        if ($null -eq $Validator -or (& $Validator $value)) {
            return $value
        }
        Write-Warn "Bad value. Fix it."
    }
}

function Read-VersionValue([string] $Current) {
    $base = if ([string]::IsNullOrWhiteSpace($Current)) { "1.0.0" } else { $Current }
    $next = Step-Version $base
    while ($true) {
        $value = Read-Host "Version current $base, blank = $next"
        if ([string]::IsNullOrWhiteSpace($value)) {
            return $next
        }
        if (Test-Version $value) {
            return $value
        }
        Write-Warn "Bad version. Fix it."
    }
}

function Test-ModId([string] $Value) {
    return $Value -match "^[a-z][a-z0-9_-]{1,63}$"
}

function Test-JavaPackage([string] $Value) {
    return $Value -match "^[a-zA-Z_][a-zA-Z0-9_]*(\.[a-zA-Z_][a-zA-Z0-9_]*)+$"
}

function Test-Version([string] $Value) {
    return $Value -match "^[0-9A-Za-z][0-9A-Za-z._+-]*$"
}

function Step-Version([string] $Version) {
    if ([string]::IsNullOrWhiteSpace($Version)) {
        return "1.0.0"
    }
    $match = [regex]::Match($Version, "^(.*?)(\d+)(\D*)$")
    if (-not $match.Success) {
        return "$Version.1"
    }
    $prefix = $match.Groups[1].Value
    $digits = $match.Groups[2].Value
    $suffix = $match.Groups[3].Value
    $next = ([int64] $digits) + 1
    $nextText = [string] $next
    if ($digits.Length -gt 1 -and $nextText.Length -lt $digits.Length) {
        $nextText = $nextText.PadLeft($digits.Length, "0")
    }
    return "$prefix$nextText$suffix"
}

function ConvertTo-ModId([string] $Name) {
    $id = $Name.ToLowerInvariant() -replace "[^a-z0-9_-]+", "-"
    $id = $id.Trim("-_")
    if ([string]::IsNullOrWhiteSpace($id)) {
        return "my-autism-addon"
    }
    if ($id[0] -notmatch "[a-z]") {
        $id = "addon-$id"
    }
    if ($id.Length -gt 64) {
        $id = $id.Substring(0, 64).Trim("-_")
    }
    return $id
}

function Get-TextFile([string] $Path) {
    return Get-Content -LiteralPath $Path -Raw
}

function Set-TextFile([string] $Path, [string] $Text) {
    $fullPath = if (Test-Path -LiteralPath $Path) {
        (Resolve-Path -LiteralPath $Path).Path
    } else {
        [System.IO.Path]::GetFullPath($Path)
    }
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($fullPath, $Text, $encoding)
}

function Get-JsonFile([string] $Path) {
    return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
}

function Save-JsonFile([string] $Path, $Json) {
    $text = $Json | ConvertTo-Json -Depth 64
    $text = $text.Replace("\u003c", "<").Replace("\u003e", ">").Replace("\u0026", "&")
    Set-TextFile $Path ($text + [Environment]::NewLine)
}

function Get-TomlVersion([string] $Path, [string] $Key) {
    $text = Get-TextFile $Path
    $match = [regex]::Match($text, "(?m)^\s*$([regex]::Escape($Key))\s*=\s*`"([^`"]+)`"")
    if ($match.Success) {
        return $match.Groups[1].Value
    }
    return ""
}

function Set-TomlVersion([string] $Path, [string] $Key, [string] $Value) {
    $text = Get-TextFile $Path
    $pattern = "(?m)^(\s*$([regex]::Escape($Key))\s*=\s*)`"[^`"]*`""
    if ($text -match $pattern) {
        $text = [regex]::Replace($text, $pattern, "`${1}`"$Value`"")
    } else {
        $text += [Environment]::NewLine + "$Key = `"$Value`"" + [Environment]::NewLine
    }
    Set-TextFile $Path $text
}

function Set-PropertiesValue([string] $Path, [string] $Key, [string] $Value) {
    $text = Get-TextFile $Path
    $pattern = "(?m)^$([regex]::Escape($Key))=.*$"
    if ($text -match $pattern) {
        $text = [regex]::Replace($text, $pattern, "$Key=$Value")
    } else {
        $text += [Environment]::NewLine + "$Key=$Value" + [Environment]::NewLine
    }
    Set-TextFile $Path $text
}

function Set-RootProjectName([string] $Path, [string] $Name) {
    $text = Get-TextFile $Path
    $pattern = "(?m)^(rootProject\.name\s*=\s*)`"[^`"]*`""
    if ($text -match $pattern) {
        $text = [regex]::Replace($text, $pattern, "`${1}`"$Name`"")
    } else {
        $text += [Environment]::NewLine + "rootProject.name = `"$Name`"" + [Environment]::NewLine
    }
    Set-TextFile $Path $text
}

function Get-FirstJavaPackage([string] $Project) {
    $entry = Join-Path $Project "src/main/resources/fabric.mod.json"
    if (Test-Path -LiteralPath $entry -PathType Leaf) {
        $json = Get-JsonFile $entry
        $classes = @()
        if ($json.entrypoints.client) { $classes += @($json.entrypoints.client) }
        if ($json.entrypoints.autism) { $classes += @($json.entrypoints.autism) }
        foreach ($class in $classes) {
            if ($class -match "^(.+)\.[^.]+$") {
                return $matches[1]
            }
        }
    }

    $java = Get-ChildItem -LiteralPath (Join-Path $Project "src/main/java") -Recurse -Filter *.java -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($java) {
        $match = [regex]::Match((Get-TextFile $java.FullName), "(?m)^\s*package\s+([^;]+);")
        if ($match.Success) {
            return $match.Groups[1].Value.Trim()
        }
    }
    return "com.example.addon"
}

function Get-ClassSimpleName([string] $ClassName, [string] $Fallback) {
    if ($ClassName -match "\.([^.]+)$") {
        return $matches[1]
    }
    return $Fallback
}

function Replace-InProjectFiles([string] $Project, [string] $Old, [string] $New) {
    if ([string]::IsNullOrWhiteSpace($Old) -or $Old -eq $New) {
        return
    }
    $extensions = @(".java", ".json", ".kts", ".toml", ".properties", ".md", ".yml", ".yaml")
    $files = Get-ChildItem -LiteralPath $Project -Recurse -File -Force | Where-Object {
        $_.FullName -notmatch "\\(build|\.gradle)\\" -and $extensions -contains $_.Extension
    }
    foreach ($file in $files) {
        $text = Get-TextFile $file.FullName
        if ($text.Contains($Old)) {
            Set-TextFile $file.FullName ($text.Replace($Old, $New))
        }
    }
}

function Move-JavaPackage([string] $Project, [string] $OldPackage, [string] $NewPackage) {
    if ($OldPackage -eq $NewPackage) {
        return
    }
    $srcRoot = Join-Path $Project "src/main/java"
    $oldPath = Join-Path $srcRoot ($OldPackage.Replace(".", [System.IO.Path]::DirectorySeparatorChar))
    $newPath = Join-Path $srcRoot ($NewPackage.Replace(".", [System.IO.Path]::DirectorySeparatorChar))
    if (-not (Test-Path -LiteralPath $oldPath -PathType Container)) {
        return
    }
    if (Test-Path -LiteralPath $newPath) {
        Write-Warn "Package path already exists. Not moving files."
        Write-PathLine "Path" $newPath
        return
    }

    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $newPath) | Out-Null
    Move-Item -LiteralPath $oldPath -Destination $newPath

    $cursor = Split-Path -Parent $oldPath
    while ($cursor -and $cursor.StartsWith($srcRoot) -and $cursor -ne $srcRoot) {
        $children = Get-ChildItem -LiteralPath $cursor -Force
        if ($children.Count -gt 0) {
            break
        }
        $next = Split-Path -Parent $cursor
        Remove-Item -LiteralPath $cursor
        $cursor = $next
    }
}

function Copy-AddonTemplate([string] $TemplateName, [string] $TargetPath) {
    $source = Join-Path $RepoRoot "addon-templates/$TemplateName"
    if (-not (Test-Path -LiteralPath $source -PathType Container)) {
        Write-PathLine "Missing template" $source
        throw "Template not found."
    }
    if (Test-Path -LiteralPath $TargetPath) {
        Write-Warn "Output already exists. Editing it in place."
        Write-PathLine "Path" $TargetPath
        return
    }
    New-Item -ItemType Directory -Force -Path $TargetPath | Out-Null
    $sourceFull = [System.IO.Path]::GetFullPath($source)
    $targetFull = [System.IO.Path]::GetFullPath($TargetPath)
    Get-ChildItem -LiteralPath $sourceFull -Recurse -Force | Where-Object {
        $_.FullName -notmatch "\\(build|\.gradle)($|\\)"
    } | ForEach-Object {
        $relative = $_.FullName.Substring($sourceFull.Length).TrimStart("\", "/")
        $destination = Join-Path $targetFull $relative
        if ($_.PSIsContainer) {
            New-Item -ItemType Directory -Force -Path $destination | Out-Null
        } else {
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
            Copy-Item -LiteralPath $_.FullName -Destination $destination -Force
        }
    }
}

function Get-AddonProjectPath {
    if (-not [string]::IsNullOrWhiteSpace($ProjectPath)) {
        return Resolve-FullPath $ProjectPath
    }
    if (-not [string]::IsNullOrWhiteSpace($OutputPath)) {
        $target = Resolve-FullPath $OutputPath
        Copy-AddonTemplate $Template $target
        return $target
    }
    return Join-Path $RepoRoot "addon-templates/$Template"
}

function Get-SetupProjectPath([string] $NameForOutput) {
    if (-not [string]::IsNullOrWhiteSpace($ProjectPath)) {
        $project = Resolve-FullPath $ProjectPath
        if (Test-ShippedTemplatePath $project) {
            throw "No. The shipped templates stay clean. Use -OutputPath to copy one, or pick a real addon folder."
        }
        return $project
    }
    if ([string]::IsNullOrWhiteSpace($OutputPath)) {
        $OutputPath = Read-Value "Output folder" (Get-DefaultAddonOutputPath $Template $NameForOutput) { param($v) -not [string]::IsNullOrWhiteSpace($v) }
    }
    $target = Resolve-FullPath $OutputPath
    if (Test-ShippedTemplatePath $target) {
        throw "No. The shipped templates stay clean. Pick a different output folder."
    }
    Copy-AddonTemplate $Template $target
    return $target
}

function Configure-Addon {
    if ([string]::IsNullOrWhiteSpace($AddonName)) {
        $defaultName = if ($Template -eq "advanced") { "My Advanced Addon" } else { "My Addon" }
        $AddonName = Read-Value "Addon name" $defaultName { param($v) -not [string]::IsNullOrWhiteSpace($v) }
    }

    $project = Get-SetupProjectPath $AddonName
    if (-not (Test-Path -LiteralPath $project -PathType Container)) {
        Write-PathLine "Missing addon folder" $project
        throw "Addon project not found."
    }

    $fabricPath = Join-Path $project "src/main/resources/fabric.mod.json"
    $versionsPath = Join-Path $project "gradle/libs.versions.toml"
    $propertiesPath = Join-Path $project "gradle.properties"
    $settingsPath = Join-Path $project "settings.gradle.kts"
    foreach ($required in @($fabricPath, $versionsPath, $propertiesPath, $settingsPath)) {
        if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
            Write-PathLine "Missing file" $required
            throw "Missing required addon file."
        }
    }

    $fabric = Get-JsonFile $fabricPath
    $oldId = [string] $fabric.id
    $oldName = [string] $fabric.name
    $oldPackage = Get-FirstJavaPackage $project
    $oldVersion = Get-TomlVersion $versionsPath "mod-version"
    $oldClientClass = if ($fabric.entrypoints.client) { [string] @($fabric.entrypoints.client)[0] } else { "$oldPackage.Init" }
    $oldAddonClass = if ($fabric.entrypoints.autism) { [string] @($fabric.entrypoints.autism)[0] } else { "$oldPackage.Addon" }
    $originalMixinName = if ($fabric.mixins -and @($fabric.mixins).Count -gt 0) { [string] @($fabric.mixins)[0] } else { "" }
    $clientSimple = Get-ClassSimpleName $oldClientClass "Init"
    $addonSimple = Get-ClassSimpleName $oldAddonClass "Addon"

    if ([string]::IsNullOrWhiteSpace($AddonId)) {
        $suggestedId = if ($oldId -match "template") { ConvertTo-ModId $AddonName } else { $oldId }
        if ($Advanced) {
            $AddonId = Read-Value "Addon id" $suggestedId { param($v) Test-ModId $v }
        } else {
            $AddonId = $suggestedId
        }
    }
    if ([string]::IsNullOrWhiteSpace($AddonVersion)) {
        $suggestedVersion = if ([string]::IsNullOrWhiteSpace($oldVersion)) { "1.0.0" } else { $oldVersion }
        $AddonVersion = Read-VersionValue $suggestedVersion
    }
    if ([string]::IsNullOrWhiteSpace($Package)) {
        $suggestedPackage = "com.$(ConvertTo-PackageSegment $AddonId).addon"
        if ($Advanced) {
            $Package = Read-Value "Java package" $suggestedPackage { param($v) Test-JavaPackage $v }
        } else {
            $Package = $suggestedPackage
        }
    }
    if ([string]::IsNullOrWhiteSpace($MavenGroup)) {
        if ($Advanced) {
            $MavenGroup = Read-Value "Maven group" $Package { param($v) Test-JavaPackage $v }
        } else {
            $MavenGroup = $Package
        }
    }
    if ([string]::IsNullOrWhiteSpace($ArchiveName)) {
        if ($Advanced) {
            $ArchiveName = Read-Value "Jar/archive base name" $AddonId { param($v) Test-ModId $v }
        } else {
            $ArchiveName = $AddonId
        }
    }
    if ([string]::IsNullOrWhiteSpace($Author)) {
        $Author = Read-Value "Author" (Get-DefaultAuthor) { param($v) -not [string]::IsNullOrWhiteSpace($v) }
    }
    if ([string]::IsNullOrWhiteSpace($Description)) {
        $suggestedDescription = "$AddonName addon for AUTISM Client."
        if ($Advanced) {
            $Description = Read-Value "Description" $suggestedDescription { param($v) -not [string]::IsNullOrWhiteSpace($v) }
        } else {
            $Description = $suggestedDescription
        }
    }

    Replace-InProjectFiles $project $oldPackage $Package
    Replace-InProjectFiles $project $oldId $AddonId
    Move-JavaPackage $project $oldPackage $Package

    $fabric = Get-JsonFile $fabricPath
    $fabric.id = $AddonId
    $fabric.name = $AddonName
    $fabric.version = '${version}'
    $fabric.description = $Description
    $fabric.authors = @($Author)
    if (-not $fabric.entrypoints) {
        $fabric | Add-Member -MemberType NoteProperty -Name entrypoints -Value ([pscustomobject]@{})
    }
    $fabric.entrypoints.client = @("$Package.$clientSimple")
    $fabric.entrypoints.autism = @("$Package.$addonSimple")

    if ($fabric.mixins -and @($fabric.mixins).Count -gt 0) {
        $oldMixinName = if ([string]::IsNullOrWhiteSpace($originalMixinName)) { [string] @($fabric.mixins)[0] } else { $originalMixinName }
        $newMixinName = "$AddonId.mixins.json"
        $resourceDir = Join-Path $project "src/main/resources"
        $oldMixinPath = Join-Path $resourceDir $oldMixinName
        $newMixinPath = Join-Path $resourceDir $newMixinName
        if ((Test-Path -LiteralPath $oldMixinPath -PathType Leaf) -and $oldMixinPath -ne $newMixinPath) {
            Move-Item -LiteralPath $oldMixinPath -Destination $newMixinPath -Force
        }
        if (Test-Path -LiteralPath $newMixinPath -PathType Leaf) {
            $mixinJson = Get-JsonFile $newMixinPath
            $mixinJson.package = "$Package.mixin"
            Save-JsonFile $newMixinPath $mixinJson
        }
        $fabric.mixins = @($newMixinName)
    }

    Save-JsonFile $fabricPath $fabric
    Set-TomlVersion $versionsPath "mod-version" $AddonVersion
    Set-PropertiesValue $propertiesPath "maven_group" $MavenGroup
    Set-PropertiesValue $propertiesPath "archives_base_name" $ArchiveName
    Set-RootProjectName $settingsPath $AddonId

    Write-Ok "Addon rewritten."
    Write-PathLine "Addon folder" $project
    Write-Host "  id:      $AddonId"
    Write-Host "  name:    $AddonName"
    Write-Host "  version: $AddonVersion"
    Write-Host "  package: $Package"
    Write-Host ""
    Write-Host "Next:" -ForegroundColor Red
    Write-Host ".\addon-templates\addon-toolkit.ps1 -Action Build -ProjectPath `"$project`"" -ForegroundColor White
    Write-PathLine "Jar folder" (Join-Path $project "build\libs")
    $script:LastConfiguredProject = $project
}

function Add-Failure([System.Collections.Generic.List[string]] $Failures, [string] $Message) {
    $Failures.Add($Message) | Out-Null
}

function Add-Warning([System.Collections.Generic.List[string]] $Warnings, [string] $Message) {
    $Warnings.Add($Message) | Out-Null
}

function Test-AddonProject([string] $Project, [switch] $AllowTemplateNames) {
    $failures = New-Object System.Collections.Generic.List[string]
    $warnings = New-Object System.Collections.Generic.List[string]

    if (-not (Test-Path -LiteralPath $Project -PathType Container)) {
        Add-Failure $failures "Project folder does not exist."
        Add-Failure $failures $Project
        return [pscustomobject]@{ Failures = $failures; Warnings = $warnings }
    }

    $required = @(
        "build.gradle.kts",
        "settings.gradle.kts",
        "gradle.properties",
        "gradle/libs.versions.toml",
        "gradlew.bat",
        "src/main/resources/fabric.mod.json"
    )
    foreach ($item in $required) {
        if (-not (Test-Path -LiteralPath (Join-Path $Project $item) -PathType Leaf)) {
            Add-Failure $failures "Missing $item"
        }
    }

    $fabricPath = Join-Path $Project "src/main/resources/fabric.mod.json"
    if (Test-Path -LiteralPath $fabricPath -PathType Leaf) {
        try {
            $fabric = Get-JsonFile $fabricPath
            if (-not (Test-ModId ([string] $fabric.id))) {
                Add-Failure $failures "fabric.mod.json id is invalid: $($fabric.id)"
            }
            if ([string]::IsNullOrWhiteSpace([string] $fabric.name)) {
                Add-Failure $failures "fabric.mod.json name is empty."
            }
            if (-not $fabric.entrypoints.autism) {
                Add-Failure $failures "Missing autism entrypoint in fabric.mod.json."
            } else {
                foreach ($entry in @($fabric.entrypoints.autism)) {
                    $path = Join-Path $Project ("src/main/java/" + ([string] $entry).Replace(".", "/") + ".java")
                    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
                        Add-Failure $failures "Autism entrypoint class file is missing: $entry"
                    }
                }
            }
            if (-not $fabric.depends.autism) {
                Add-Failure $failures "fabric.mod.json must depend on autism."
            }
            if (-not $AllowTemplateNames) {
                if ([string] $fabric.id -match "template|example") {
                    Add-Warning $warnings "Addon id still looks like a template/example."
                }
                if ([string] $fabric.name -match "Template|Example") {
                    Add-Warning $warnings "Addon name still looks like a template/example."
                }
                if (@($fabric.authors) -contains "You") {
                    Add-Warning $warnings "Author is still 'You'."
                }
            }
        } catch {
            Add-Failure $failures "fabric.mod.json is not valid JSON: $($_.Exception.Message)"
        }
    }

    $versionsPath = Join-Path $Project "gradle/libs.versions.toml"
    if (Test-Path -LiteralPath $versionsPath -PathType Leaf) {
        $addonVersion = Get-TomlVersion $versionsPath "mod-version"
        $hostVersion = Get-TomlVersion $versionsPath "autism"
        if (-not (Test-Version $addonVersion)) {
            Add-Failure $failures "Addon mod-version is missing or invalid."
        }
        if ([string]::IsNullOrWhiteSpace($hostVersion)) {
            Add-Failure $failures "AUTISM dependency version is missing from gradle/libs.versions.toml."
        }
    }

    if (Test-Path -LiteralPath (Join-Path $Project ".gradle")) {
        Add-Warning $warnings ".gradle cache exists locally. Ignored; do not upload it."
    }
    if (Test-Path -LiteralPath (Join-Path $Project "build")) {
        Add-Warning $warnings "build output exists locally. Ignored; do not upload it."
    }
    if (Test-Path -LiteralPath (Join-Path $Project ".github")) {
        Add-Warning $warnings ".github exists inside this addon project. Fine for a copied template, but it is not root repo CI unless you move it."
    }

    $files = Get-ChildItem -LiteralPath $Project -Recurse -File -Force | Where-Object {
        $_.FullName -notmatch "\\(build|\.gradle)\\" -and
        $_.Extension -in @(".java", ".json", ".md", ".kts", ".toml", ".properties", ".yml", ".yaml")
    }
    $templateLeftovers = @(
        "autism-minimal-addon-template",
        "autism-advanced-addon-template",
        "AUTISM Minimal Addon Template",
        "AUTISM Advanced Addon Template"
    )
    foreach ($file in $files) {
        $text = Get-TextFile $file.FullName
        if ($text -match "[\u00C2\u00C3\u00E2]") {
            Add-Warning $warnings "Mojibake-looking text in $($file.FullName)"
        }
        if (-not $AllowTemplateNames) {
            foreach ($leftover in $templateLeftovers) {
                if ($text.Contains($leftover)) {
                    Add-Warning $warnings "Template leftover '$leftover' in $($file.FullName)"
                    break
                }
            }
        }
    }

    return [pscustomobject]@{ Failures = $failures; Warnings = $warnings }
}

function Scan-Addon {
    $project = Get-AddonProjectPath
    $result = Test-AddonProject $project
    Write-Info "Scanned addon project."
    Write-PathLine "Addon folder" $project
    foreach ($warning in $result.Warnings) {
        Write-Warn "Heads up: $warning"
    }
    if ($result.Failures.Count -gt 0) {
        Write-Bad "Scan found broken stuff:"
        foreach ($failure in $result.Failures) {
            Write-FailureItem $failure
        }
        throw "Scan failed."
    }
    Write-Ok "Scan clean."
}

function Validate-AddonSystem {
    $failures = New-Object System.Collections.Generic.List[string]
    $warnings = New-Object System.Collections.Generic.List[string]

    $rootRequired = @(
        "src/main/java/autismclient/addons/AddonManager.java",
        "src/main/java/autismclient/api/AutismAddon.java",
        "src/main/java/autismclient/api/SimpleAddon.java",
        "src/main/java/autismclient/api/AutismAddons.java",
        "src/main/java/autismclient/api/ApiVersion.java",
        "src/main/java/autismclient/api/module/SimpleModule.java",
        "src/main/java/autismclient/api/macro/SimpleAction.java",
        "src/main/java/autismclient/api/macro/SimpleCondition.java",
        "src/main/java/autismclient/api/macro/MacroActionRegistry.java",
        "src/main/java/autismclient/api/macro/MacroPresetRegistry.java",
        "src/main/java/autismclient/api/hud/HudElements.java",
        "src/main/java/autismclient/api/event/AddonEvents.java",
        "src/main/java/autismclient/gui/screen/AutismAddonsScreen.java",
        "src/main/java/autismclient/util/macro/MissingAddonAction.java",
        "addon-templates/README.md",
        "addon-templates/addon-toolkit.ps1"
    )
    foreach ($file in $rootRequired) {
        if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot $file) -PathType Leaf)) {
            Add-Failure $failures "Missing $file"
        }
    }

    $gitignore = Join-Path $RepoRoot ".gitignore"
    if (Test-Path -LiteralPath $gitignore -PathType Leaf) {
        $ignoreText = Get-TextFile $gitignore
        foreach ($pattern in @("!/src/", "!/addon-templates/", "!/addon-templates/**", "/addon-templates/**/build/", "/addon-templates/**/.gradle/", "/mc-src*/")) {
            if (-not $ignoreText.Contains($pattern)) {
                Add-Failure $failures ".gitignore is missing $pattern"
            }
        }
    } else {
        Add-Failure $failures "Missing .gitignore"
    }

    $mainVersion = Get-TomlVersion (Join-Path $RepoRoot "gradle/libs.versions.toml") "mod-version"
    foreach ($templateName in @("minimal", "advanced")) {
        $project = Join-Path $RepoRoot "addon-templates/$templateName"
        $result = Test-AddonProject $project -AllowTemplateNames
        foreach ($failure in $result.Failures) {
            Add-Failure $failures "$templateName template: $failure"
        }
        foreach ($warning in $result.Warnings) {
            Add-Warning $warnings "$templateName template: $warning"
        }
        $targetVersion = Get-TomlVersion (Join-Path $project "gradle/libs.versions.toml") "autism"
        if ($mainVersion -and $targetVersion -and $mainVersion -ne $targetVersion) {
            Add-Failure $failures "$templateName template targets AUTISM $targetVersion, main client is $mainVersion."
        }
    }

    foreach ($warning in $warnings) {
        Write-Warn "Heads up: $warning"
    }
    if ($failures.Count -gt 0) {
        Write-Bad "Addon system is broken:"
        foreach ($failure in $failures) {
            Write-FailureItem $failure
        }
        throw "Addon system validation failed."
    }
    Write-Ok "Addon system clean."
}

function Invoke-Gradle([string] $Project, [string[]] $GradleArgs) {
    $gradlew = Join-Path $Project "gradlew.bat"
    if (-not (Test-Path -LiteralPath $gradlew -PathType Leaf)) {
        Write-PathLine "Missing Gradle wrapper" $gradlew
        throw "Missing Gradle wrapper."
    }
    Push-Location $Project
    try {
        & $gradlew @GradleArgs
        if ($LASTEXITCODE -ne 0) {
            Write-PathLine "Gradle project" $Project
            throw "Gradle failed with exit code $LASTEXITCODE."
        }
    } finally {
        Pop-Location
    }
}

function Publish-Api {
    Write-Info "Publishing client API to Maven Local."
    Invoke-Gradle $RepoRoot @("publishToMavenLocal", "--no-daemon")
    Write-Ok "Client API published."
}

function Build-Addon {
    $project = Get-AddonProjectPath
    if (-not (Test-Path -LiteralPath $project -PathType Container)) {
        Write-PathLine "Addon folder" $project
        throw "Wrong addon folder."
    }
    $gradlew = [System.IO.Path]::Combine($project, "gradlew.bat")
    if (-not (Test-Path -LiteralPath $gradlew -PathType Leaf)) {
        Write-PathLine "Addon folder" $project
        throw "No Gradle wrapper there. Wrong addon folder."
    }
    if (-not $NoPublish) {
        Publish-Api
    }
    Write-Info "Building addon."
    Write-PathLine "Addon folder" $project
    Invoke-Gradle $project @("build", "--no-daemon")
    Write-Ok "Addon built."
    Write-PathLine "Addon folder" $project
    Write-PathLine "Jar folder" (Join-Path $project "build\libs")
}

function Build-AllTemplates {
    if (-not $NoPublish) {
        Publish-Api
    }
    foreach ($templateName in @("minimal", "advanced")) {
        $project = Join-Path $RepoRoot "addon-templates/$templateName"
        Write-Info "Building $templateName template."
        Invoke-Gradle $project @("build", "--no-daemon")
        Write-Ok "$templateName template built."
    }
}

function Clean-Addon {
    $project = Get-AddonProjectPath
    foreach ($folder in @(".gradle", "build")) {
        $path = Join-Path $project $folder
        if (Test-Path -LiteralPath $path) {
            if ($Yes -or ((Read-Host "Remove $path ? Type YES") -eq "YES")) {
                Remove-Item -LiteralPath $path -Recurse -Force
                Write-Ok "Deleted local build junk."
                Write-PathLine "Path" $path
            }
        }
    }
}

function Invoke-MenuChoice([scriptblock] $Body) {
    try {
        & $Body
    } catch {
        Write-Bad $_.Exception.Message
        if ($DebugStack -and $_.ScriptStackTrace) {
            Write-Warn $_.ScriptStackTrace
        }
        [void] (Read-Host "Press Enter. The error stays here until you do")
    }
}

function Show-Menu {
    $script:LastAddonPath = ""
    while ($true) {
        Write-Host ""
        Write-Host "AUTISM ADDON KIT" -ForegroundColor Red
        Write-Host "1. New addon"
        Write-Host "2. Build addon"
        Write-Host "3. Scan addon"
        Write-Host "4. Test shipped templates"
        Write-Host "5. Publish client API"
        Write-Host "6. Delete local build junk"
        Write-Host "0. Leave"
        $choice = Read-Host ">"
        switch ($choice) {
            "1" {
                Invoke-MenuChoice {
                    $script:Template = Read-Value "Template minimal/advanced" $Template { param($v) $v -in @("minimal", "advanced") }
                    $script:ProjectPath = ""
                    $script:OutputPath = ""
                    Configure-Addon
                    $script:LastAddonPath = $script:LastConfiguredProject
                }
            }
            "2" {
                Invoke-MenuChoice {
                    $defaultPath = if ([string]::IsNullOrWhiteSpace($script:LastAddonPath)) { Get-DefaultAddonOutputPath $Template "" } else { $script:LastAddonPath }
                    $script:ProjectPath = Read-Value "Addon folder" $defaultPath { param($v) -not [string]::IsNullOrWhiteSpace($v) }
                    Build-Addon
                }
            }
            "3" {
                Invoke-MenuChoice {
                    $defaultPath = if ([string]::IsNullOrWhiteSpace($script:LastAddonPath)) { Get-DefaultAddonOutputPath $Template "" } else { $script:LastAddonPath }
                    $script:ProjectPath = Read-Value "Addon folder" $defaultPath { param($v) -not [string]::IsNullOrWhiteSpace($v) }
                    Scan-Addon
                }
            }
            "4" { Invoke-MenuChoice { Validate-AddonSystem; Build-AllTemplates } }
            "5" { Invoke-MenuChoice { Publish-Api } }
            "6" {
                Invoke-MenuChoice {
                    $defaultPath = if ([string]::IsNullOrWhiteSpace($script:LastAddonPath)) { Get-DefaultAddonOutputPath $Template "" } else { $script:LastAddonPath }
                    $script:ProjectPath = Read-Value "Addon folder" $defaultPath { param($v) -not [string]::IsNullOrWhiteSpace($v) }
                    Clean-Addon
                }
            }
            "0" { return }
            default { Write-Warn "Bad choice." }
        }
    }
}

function Invoke-RequestedAction {
    switch ($Action) {
        "Menu" { Show-Menu }
        "Setup" { Configure-Addon }
        "Scan" { Scan-Addon }
        "Validate" { Validate-AddonSystem }
        "PublishApi" { Publish-Api }
        "Build" { Build-Addon }
        "BuildAll" { Build-AllTemplates }
        "Clean" { Clean-Addon }
    }
}

try {
    Invoke-RequestedAction
} catch {
    Write-Bad $_.Exception.Message
    if ($DebugStack -and $_.ScriptStackTrace) {
        Write-Warn $_.ScriptStackTrace
    }
    if ($Action -eq "Menu" -or (-not $NonInteractive -and -not $env:CI)) {
        [void] (Read-Host "Press Enter. The error stays here until you do")
    }
    exit 1
}
