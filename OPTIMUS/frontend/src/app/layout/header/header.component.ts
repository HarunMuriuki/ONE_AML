import { DOCUMENT } from '@angular/common';
import {
  Component,
  Inject,
  ElementRef,
  OnInit,
  Renderer2,
  AfterViewInit,
  HostListener,
} from '@angular/core';
import { Router } from '@angular/router';
import { ConfigService } from 'src/app/config/config.service';
import { Role } from 'src/app/core/models/role';
import { AuthService } from 'src/app/core/service/auth.service';
import { LanguageService } from 'src/app/core/service/language.service';
import { TokenStorageService } from 'src/app/core/service/token-storage.service';
import { UnsubscribeOnDestroyAdapter } from 'src/app/shared/UnsubscribeOnDestroyAdapter';

@Component({
  selector: 'app-header',
  templateUrl: './header.component.html',
  styleUrls: ['./header.component.scss'],
})
export class HeaderComponent
  extends UnsubscribeOnDestroyAdapter
  implements OnInit, AfterViewInit
{
  public config: any = {};
  userImg: string;
  homePage: string;
  isNavbarCollapsed = true;
  flagvalue;
  countryName;
  langStoreValue: string;
  defaultFlag: string;
  isOpenSidebar: boolean = false;
  userName: string;
  date = new Date();
  userRole: any;
  tenantId: any;
  cooperativeId: any;
  branchName: string;
  Role = Role;

  constructor(
    @Inject(DOCUMENT) private document: Document,
    private renderer: Renderer2,
    public elementRef: ElementRef,
    private configService: ConfigService,
    private authService: AuthService,
    private router: Router,
    public languageService: LanguageService,
    private tokenStorage: TokenStorageService
  ) {
    super();
  }

  listLang = [
    { text: 'English', flag: 'assets/images/flags/us.jpg', lang: 'en' },
    { text: 'Spanish', flag: 'assets/images/flags/spain.jpg', lang: 'es' },
    { text: 'German', flag: 'assets/images/flags/germany.jpg', lang: 'de' },
  ];

  ngOnInit() {
    const mediaQuery = window.matchMedia('(min-width: 1030px)');
    mediaQuery.addEventListener('change', () =>
      this.onResize(new Event('matchMediaChange'))
    );
    this.config = this.configService.configData;
    const user = this.tokenStorage.getUser();

    this.userRole =
      typeof user.roles[0] === 'string' ? user.roles[0] : user.roles[0].name;

    this.userName = user.username;
    this.tenantId = user.tenantId;
    this.userImg = 'assets/images/user/profile_img.png';

    this.homePage = 'admin/dashboard';

    this.langStoreValue = localStorage.getItem('lang');
    const val = this.listLang.filter((x) => x.lang === this.langStoreValue);
    this.countryName = val.map((element) => element.text);
    if (val.length === 0) {
      if (this.flagvalue === undefined) {
        this.defaultFlag = 'assets/images/flags/us.jpg';
      }
    } else {
      this.flagvalue = val.map((element) => element.flag);
    }
  }

  ngAfterViewInit() {
    this.initializeSidebarState();
    this.onResize(new Event('init'));
  }

  initializeSidebarState() {
    if (localStorage.getItem('theme')) {
      this.renderer.removeClass(this.document.body, this.config.layout.variant);
      this.renderer.addClass(this.document.body, localStorage.getItem('theme'));
    } else {
      this.renderer.addClass(this.document.body, this.config.layout.variant);
    }

    if (localStorage.getItem('menuOption')) {
      this.renderer.addClass(
        this.document.body,
        localStorage.getItem('menuOption')
      );
    } else {
      this.renderer.addClass(
        this.document.body,
        'menu_' + this.config.layout.sidebar.backgroundColor
      );
    }

    if (localStorage.getItem('choose_logoheader')) {
      this.renderer.addClass(
        this.document.body,
        localStorage.getItem('choose_logoheader')
      );
    } else {
      this.renderer.addClass(
        this.document.body,
        'logo-' + this.config.layout.logo_bg_color
      );
    }

    const isDesktop = window.innerWidth >= 1030;
    const sidebarStatus = localStorage.getItem('sidebar_status');

    if (isDesktop) {
      // Desktop: Sidebar open by default unless explicitly closed
      if (sidebarStatus === 'close') {
        this.renderer.addClass(this.document.body, 'side-closed');
        this.renderer.addClass(this.document.body, 'submenu-closed');
        this.isOpenSidebar = false;
      } else {
        this.renderer.removeClass(this.document.body, 'side-closed');
        this.renderer.removeClass(this.document.body, 'submenu-closed');
        this.isOpenSidebar = true;
        localStorage.setItem('sidebar_status', 'open'); // Ensure consistency
      }
    } else {
      // Mobile/Tablet: Sidebar closed by default
      this.renderer.addClass(this.document.body, 'side-closed');
      this.renderer.addClass(this.document.body, 'submenu-closed');
      this.isOpenSidebar = false;
      if (sidebarStatus === 'open') {
        this.renderer.removeClass(this.document.body, 'side-closed');
        this.renderer.removeClass(this.document.body, 'submenu-closed');
        this.isOpenSidebar = true;
      }
    }
  }

  callSidemenuCollapse() {
    this.isOpenSidebar = !this.isOpenSidebar;

    if (this.isOpenSidebar) {
      this.renderer.removeClass(this.document.body, 'side-closed');
      this.renderer.removeClass(this.document.body, 'submenu-closed');
      this.renderer.removeClass(this.document.body, 'ls-closed');
      localStorage.setItem('sidebar_status', 'open');
    } else {
      this.closeSidebar();
    }

    console.log(
      'Sidebar toggled:',
      this.isOpenSidebar,
      this.document.body.classList
    );
  }

  callFullscreen() {
    const docElm = document.documentElement;

    // Check if the document is currently in fullscreen mode
    if (
      !document.fullscreenElement &&
      !(document as any).webkitFullscreenElement &&
      !(document as any).mozFullScreenElement &&
      !(document as any).msFullscreenElement
    ) {
      // Request fullscreen
      if (docElm.requestFullscreen) {
        docElm.requestFullscreen();
      } else if ((docElm as any).webkitRequestFullscreen) {
        (docElm as any).webkitRequestFullscreen();
      } else if ((docElm as any).mozRequestFullScreen) {
        (docElm as any).mozRequestFullScreen();
      } else if ((docElm as any).msRequestFullscreen) {
        (docElm as any).msRequestFullscreen();
      }
    } else {
      // Exit fullscreen
      if (document.exitFullscreen) {
        document.exitFullscreen();
      } else if ((document as any).webkitExitFullscreen) {
        (document as any).webkitExitFullscreen();
      } else if ((document as any).mozCancelFullScreen) {
        (document as any).mozCancelFullScreen();
      } else if ((document as any).msExitFullscreen) {
        (document as any).msExitFullscreen();
      }
    }
  }

  setLanguage(text: string, lang: string, flag: string) {
    this.countryName = text;
    this.flagvalue = flag;
    this.langStoreValue = lang;
    this.languageService.setLanguage(lang);
  }

  mobileMenuSidebarOpen(event: any, className: string) {
    const hasClass = event.target.classList.contains(className);
    if (hasClass) {
      this.renderer.removeClass(this.document.body, className);
    } else {
      this.renderer.addClass(this.document.body, className);
    }
  }

  logout(): void {
    this.tokenStorage.signOut();
    this.router.navigate(['/authentication/signin']);
  }

  toSettings() {
    this.router.navigate(['/admin/users/settings']);
  }

  toProfile() {
    if (this.userRole === Role.Admin) {
      this.router.navigate(['/admin/user-profile/add-account']);
    } else {
      this.router.navigate(['/staff/user-profile/profile']);
    }
  }

  toSMSManagement() {
    this.router.navigate(['/staff/sms']);
  }

  @HostListener('document:click', ['$event'])
  onClickOutside(event: MouseEvent) {
    const sidebarEl = this.document.querySelector('.sidebar');
    const menuButton =
      this.elementRef.nativeElement.querySelector('.sidemenu-collapse');

    if (
      this.isOpenSidebar &&
      sidebarEl &&
      !sidebarEl.contains(event.target as Node) &&
      !menuButton.contains(event.target as Node) &&
      window.innerWidth < 1030 // Close on click outside for mobile and tablet
    ) {
      this.closeSidebar();
    }
  }

  @HostListener('window:resize', ['$event'])
  onResize(event: Event) {
    const width = window.innerWidth;

    if (width >= 1030) {
      this.renderer.removeClass(this.document.body, 'side-closed');
      this.renderer.removeClass(this.document.body, 'submenu-closed');
      this.renderer.removeClass(this.document.body, 'ls-closed');
      this.isOpenSidebar = true;
      localStorage.setItem('sidebar_status', 'open');
    } else {
      this.renderer.addClass(this.document.body, 'side-closed');
      this.renderer.addClass(this.document.body, 'submenu-closed');
      this.renderer.addClass(this.document.body, 'ls-closed');
      this.isOpenSidebar = false;
      localStorage.setItem('sidebar_status', 'close');
    }
  }

  closeSidebar() {
    if (window.innerWidth < 1030) {
      // Include tablet devices (up to 1029px)
      this.isOpenSidebar = false;
      this.renderer.addClass(this.document.body, 'side-closed');
      this.renderer.addClass(this.document.body, 'submenu-closed');
      this.renderer.addClass(this.document.body, 'ls-closed');
      localStorage.setItem('sidebar_status', 'close');
    }
  }
}
